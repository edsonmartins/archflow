package br.com.archflow.api.agent.qp;

import br.com.archflow.api.agent.mcp.McpAgentRunner;
import br.com.archflow.api.mcp.vendax.VendaxMcpClientProvider;
import br.com.archflow.langchain4j.mcp.McpClient;

import java.util.List;
import java.util.Map;
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

    private final McpAgentRunner runner;
    private final VendaxMcpClientProvider vendax;

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
            List<String> itensJaNoPedido) {
    }

    public record QpResult(
            String finalText,
            List<McpAgentRunner.ToolCall> toolCalls,
            String quote,                 // payload da última simular/firmar_cotacao (JSON string), se houver
            String chaveIdempotencia) {
    }

    public QpResult quote(QpRequest request) {
        String tenantId = request.tenantId() != null ? request.tenantId() : "__default__";
        McpClient client = vendax.clientFor(tenantId);

        // chaveIdempotencia gerada UMA vez e reusada em firmar_cotacao/enviar_pedido
        // (idempotência do Core sobre a tupla item/qtd/condição).
        String chaveIdempotencia = "qp-" + UUID.randomUUID();

        String systemPrompt = buildSystemPrompt(request, chaveIdempotencia);
        String userMessage = buildUserMessage(request);

        McpAgentRunner.Result result = runner.run(tenantId, systemPrompt, userMessage, client);

        String quote = extractQuote(result);
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
            1. Chame `resolver_sku` com {clienteRef, vendedorRef, modoEntrada, entrada, itensJaNoPedido}.
            2. Respeite o GATE devolvido:
               - RESOLVE: há 1 SKU confiável — prossiga com ele (o frontend pede a confirmação por toque).
               - MOSTRA: há 2-3 candidatos — devolva-os para o cliente escolher; NÃO adivinhe.
               - LISTA: devolva a lista para navegação; NÃO adivinhe.
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
