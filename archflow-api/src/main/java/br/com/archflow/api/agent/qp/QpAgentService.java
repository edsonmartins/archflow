package br.com.archflow.api.agent.qp;

import br.com.archflow.api.agent.mcp.McpAgentRunner;
import br.com.archflow.api.agent.mcp.ToolAccessPolicy;
import br.com.archflow.api.mcp.vendax.VendaxMcpClientProvider;
import br.com.archflow.langchain4j.mcp.McpClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Agente QP (Quote/Pedido): orquestra as tools do VendaX Core via MCP para
 * transformar uma mensagem do cliente numa cotação. O ArchFlow faz o
 * entendimento + orquestração + tool-calling; o Core é a autoridade de domínio
 * (preço/pedido/ERP — A-6). NÃO reimplementa nada do Core.
 *
 * <p>Fluxo que o LLM segue (guiado pelo system prompt), executando tools reais:
 * {@code resolver_sku} → (gate + P-7) → {@code registrar_resolucao} →
 * {@code simular_cotacao} → {@code firmar_cotacao} (chaveIdempotencia) →
 * {@code enviar_pedido}. US ({@code sugerir_itens}/{@code registrar_decisao},
 * honrando {@code grupo_controle}) e CS ({@code obter_eventos_operacionais})
 * são disponibilizados como tools no mesmo loop.
 */
public class QpAgentService {

    /**
     * Allowlist do agente QP — exatamente as tools do fluxo documentado acima.
     * O VendaX Core expõe mais do que isso, e crescerá; sem esta lista o agente
     * receberia toda tool nova do Core (inclusive destrutiva) sem revisão.
     * Alterar esta lista é uma mudança de escopo consciente do agente.
     */
    static final Set<String> ALLOWED_TOOLS = Set.of(
            "resolver_sku",
            "registrar_resolucao",
            "simular_cotacao",
            "firmar_cotacao",
            "enviar_pedido",
            "sugerir_itens",
            "registrar_decisao",
            "obter_eventos_operacionais");

    private final McpAgentRunner runner;
    private final VendaxMcpClientProvider vendax;
    private final ToolAccessPolicy toolPolicy = ToolAccessPolicy.allowOnly(ALLOWED_TOOLS);
    private final ObjectMapper mapper = new ObjectMapper();

    public QpAgentService(McpAgentRunner runner, VendaxMcpClientProvider vendax) {
        this.runner = runner;
        this.vendax = vendax;
    }

    public record QpRequest(
            String tenantId,
            String clienteRef,
            String vendedorRef,
            String modoEntrada,   // DITADO | TEXTO_CLIENTE | IMAGEM | PDF | SELECAO
            String entrada,       // texto normalizado (multimodal → texto é do ArchFlow)
            List<String> itensJaNoPedido,

            /**
             * Prompt vindo do Core (RFC-013), já composto com os slots do tenant e com a chave de
             * idempotência dentro. Nulo = usa o prompt embutido.
             */
            String systemPromptDoCore,

            /**
             * Chave de idempotência decidida pelo Core. Gerar aqui significaria que reprocessar o
             * mesmo invoke firma duas cotações distintas — idempotência é do domínio do Core.
             */
            String chaveIdempotenciaDoCore) {

        /** Compat: chamadas anteriores à RFC-013, sem definição nem chave. */
        public QpRequest(String tenantId, String clienteRef, String vendedorRef, String modoEntrada,
                         String entrada, List<String> itensJaNoPedido) {
            this(tenantId, clienteRef, vendedorRef, modoEntrada, entrada, itensJaNoPedido, null, null);
        }
    }

    public record QpResult(
            String finalText,
            List<McpAgentRunner.ToolCall> toolCalls,
            String quote,                 // payload da última simular/firmar_cotacao (JSON string), se houver
            String chaveIdempotencia) {
    }

    public QpResult quote(QpRequest request) {
        return quote(request, null);
    }

    public QpResult quote(QpRequest request, String definitionVersion) {
        String tenantId = request.tenantId() != null ? request.tenantId() : "__default__";
        McpClient client = vendax.clientFor(tenantId, definitionVersion);

        // chaveIdempotencia usada UMA vez e reusada em firmar_cotacao/enviar_pedido (idempotência do
        // Core sobre a tupla item/qtd/condição). Vinda do Core quando ele a decidiu — gerar aqui faria
        // um reprocessamento do mesmo invoke firmar duas cotações distintas.
        String chaveIdempotencia = request.chaveIdempotenciaDoCore() != null
                && !request.chaveIdempotenciaDoCore().isBlank()
                ? request.chaveIdempotenciaDoCore()
                : "qp-" + UUID.randomUUID();

        // O prompt do Core já vem com a chave dentro (RFC-013); o embutido a interpola aqui.
        String systemPrompt = request.systemPromptDoCore() != null
                && !request.systemPromptDoCore().isBlank()
                ? request.systemPromptDoCore()
                : buildSystemPrompt(request, chaveIdempotencia);
        String userMessage = buildUserMessage(request);

        McpAgentRunner.Result result =
                runner.run(tenantId, systemPrompt, userMessage, client, toolPolicy);

        String quote = includePendingItems(extractQuote(result), result.toolCalls(), request.entrada());
        return new QpResult(result.finalText(), result.toolCalls(), quote, chaveIdempotencia);
    }

    /** O quote é o resultado da cotação mais recente (firmar tem prioridade sobre simular). */
    private String extractQuote(McpAgentRunner.Result result) {
        McpAgentRunner.ToolCall firmada = result.lastSuccessfulCall("firmar_cotacao");
        if (firmada != null) {
            return firmada.resultText();
        }
        McpAgentRunner.ToolCall simulada = result.lastSuccessfulCall("simular_cotacao");
        return simulada != null ? simulada.resultText() : null;
    }

    /**
     * Materializa no payload da cotação os itens que o gate determinístico não resolveu.
     *
     * <p>Os candidatos pertencem ao resultado bruto de {@code resolver_sku}; pedir ao LLM para
     * repeti-los no texto final perderia exatamente o ranque e a proveniência que o Core calculou.
     * Por isso esta transformação acontece programaticamente, depois do loop. Uma cotação composta
     * apenas por pendências é válida; sem nenhuma pendência e sem simulação, {@code null} continua
     * distinguindo uma mensagem que não era pedido.</p>
     */
    String includePendingItems(String quote, List<McpAgentRunner.ToolCall> toolCalls,
                               String requestText) {
        List<ObjectNode> pending = new ArrayList<>();
        List<McpAgentRunner.ToolCall> resolverCalls = toolCalls == null ? List.of() : toolCalls.stream()
                .filter(call -> "resolver_sku".equals(call.name()) && !call.isError())
                .toList();

        for (McpAgentRunner.ToolCall call : resolverCalls) {
            JsonNode result = parseObject(call.resultText());
            if (result != null && "RESOLVE".equalsIgnoreCase(result.path("gate").asText())) {
                continue;
            }

            ObjectNode item = mapper.createObjectNode();
            JsonNode arguments = mapper.valueToTree(call.arguments() == null ? Map.of() : call.arguments());
            String text = firstText(arguments, "entrada", "texto", "item", "descricao");
            if ((text == null || text.isBlank()) && resolverCalls.size() == 1) {
                text = requestText;
            }
            if (text == null || text.isBlank()) {
                // Sem o trecho original a pendência não fecha o léxico e confunde o vendedor.
                // Não emita um objeto estruturalmente válido porém semanticamente inútil.
                continue;
            }
            item.put("texto", text);
            copyFirst(arguments, item, "qty", "qty", "qtd", "quantidade");
            copyFirst(arguments, item, "unidade", "unidade", "unit");

            ArrayNode candidates = item.putArray("candidatos");
            if (result != null && result.path("candidatos").isArray()) {
                for (JsonNode candidate : result.path("candidatos")) {
                    ObjectNode mapped = candidates.addObject();
                    copyFirst(candidate, mapped, "sku", "skuRef", "sku");
                    copyFirst(candidate, mapped, "descricao", "descricao");
                    copyFirst(candidate, mapped, "confianca", "score", "confianca");
                    copyFirst(candidate, mapped, "motivo", "motivo");
                }
            }
            pending.add(item);
        }

        if (pending.isEmpty()) {
            return quote;
        }

        ObjectNode payload = quotePayload(quote);
        ArrayNode pendingArray = payload.withArray("pendentes");
        pending.forEach(pendingArray::add);
        ArrayNode flags = payload.withArray("flags");
        boolean alreadyFlagged = false;
        for (JsonNode flag : flags) {
            if ("ITENS_PENDENTES".equals(flag.asText())) {
                alreadyFlagged = true;
                break;
            }
        }
        if (!alreadyFlagged) {
            flags.add("ITENS_PENDENTES");
        }
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Não foi possível serializar as pendências da cotação", e);
        }
    }

    private ObjectNode quotePayload(String quote) {
        if (quote == null || quote.isBlank()) {
            return mapper.createObjectNode();
        }
        JsonNode parsed = parseObject(quote);
        if (parsed instanceof ObjectNode object) {
            return object;
        }
        throw new IllegalStateException("Cotação devolvida pelo Core não é um objeto JSON");
    }

    private JsonNode parseObject(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode parsed = mapper.readTree(json);
            return parsed != null && parsed.isObject() ? parsed : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String firstText(JsonNode source, String... fields) {
        for (String field : fields) {
            JsonNode value = source.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private static void copyFirst(JsonNode source, ObjectNode target, String targetField,
                                  String... sourceFields) {
        for (String sourceField : sourceFields) {
            JsonNode value = source.path(sourceField);
            if (!value.isMissingNode() && !value.isNull()) {
                target.set(targetField, value);
                return;
            }
        }
    }

    private String buildUserMessage(QpRequest r) {
        StringBuilder sb = new StringBuilder();
        sb.append("clienteRef=").append(nullSafe(r.clienteRef()))
          .append("\nvendedorRef=").append(nullSafe(r.vendedorRef()))
          .append("\nmodoEntrada=").append(nullSafe(r.modoEntrada()))
          .append("\nentrada=").append(nullSafe(r.entrada()));
        if (r.itensJaNoPedido() != null && !r.itensJaNoPedido().isEmpty()) {
            sb.append("\nitensJaNoPedido=").append(String.join(", ", r.itensJaNoPedido()));
        }
        return sb.toString();
    }

    private String buildSystemPrompt(QpRequest r, String chaveIdempotencia) {
        // Regras de negócio (gate/P-7, US, CS) que o LLM segue chamando as tools
        // reais do VendaX Core. A UX (toque de confirmação, stepper de quantidade)
        // é do frontend — o backend NÃO dita UI, apenas devolve gate + candidatos.
        return """
            Você é o agente QP (Quote/Pedido) do VendaX. Sua função é entender o que o cliente
            pediu e montar uma cotação, chamando SEMPRE as tools do VendaX Core (a autoridade de
            domínio: SKU, preço, pedido e ERP são dele — nunca invente nada disso).

            FLUXO:
            1. Separe cada item mencionado e chame `resolver_sku` uma vez por item. Em `entrada`,
               mande exatamente o trecho como o cliente escreveu ou falou, nunca a mensagem inteira.
               Preserve quantidade e unidade nos argumentos da chamada quando estiverem presentes.
            2. Respeite o GATE devolvido:
               - RESOLVE: há 1 SKU confiável — prossiga com ele (o frontend pede a confirmação por toque).
               - MOSTRA: há 2-3 candidatos — mantenha o item como pendência; NÃO adivinhe.
               - LISTA: mantenha o item como pendência, mesmo sem candidatos; NÃO adivinhe.
            3. Após o SKU escolhido/confirmado, chame `registrar_resolucao` (fecha o loop do léxico).
            4. Enquanto o cliente monta, chame `simular_cotacao` (preço não vinculante).
            5. No compromisso do cliente, chame `firmar_cotacao` usando SEMPRE
               chaveIdempotencia="%s" (reuse exatamente esta chave). Depois `enviar_pedido`.

            UPSELL (US): só DEPOIS de um item aceito e ANTES do fechamento (nunca durante negociação
            de preço nem após evento negativo). Chame `sugerir_itens`. Se a resposta vier RETIDA
            (sugestões vazias e grupoControle=true), é holdout: NÃO invente sugestão. Quando o cliente
            decidir, chame `registrar_decisao` com {intervencaoId, decisao=ACEITA|RECUSADA|IGNORADA}.

            CS: se o cliente esfriou por corte/atraso, chame `obter_eventos_operacionais` com o clienteRef
            para entender o pós-pedido.

            REGRAS:
            - Quantidade é sempre definida por stepper/toque no frontend; não a dite.
            - Não reimplemente cálculo de preço nem regras de ERP: use as tools.
            - Se uma tool falhar, explique o erro ao cliente; não fabrique dados.
            """.formatted(chaveIdempotencia);
    }

    private static String nullSafe(String s) {
        return s != null ? s : "";
    }
}
