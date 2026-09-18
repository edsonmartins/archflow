package br.com.archflow.api.agent.vendax;

import br.com.archflow.api.agent.mcp.McpAgentRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * O upsell pré-fechamento pelo US (VendaX, {@code 02-CATALOGO} §4.7, ADR-023, ADR-038).
 *
 * <h2>Verbalizado: quem escolhe é o Core</h2>
 *
 * <p>A tool {@value #TOOL} devolve as candidatas que o Core já filtrou por disponibilidade, piso de
 * margem e afinidade. O modelo escolhe <b>uma</b> delas e escreve a frase. Ele não decide o que é
 * elegível, não calcula probabilidade e não inventa item.</p>
 *
 * <h2>O que vai no resultado vem da chamada, não da afirmação</h2>
 *
 * <p>O {@code intervencaoId} é lido do resultado da tool — <b>nunca</b> do que o modelo escreve. É a
 * mesma regra do parâmetro do NS: o id amarra a sugestão à intervenção que o Core gerou, e um id
 * inventado ou copiado de outra conversa produziria uma mensagem que o Core recusa, ou pior, que
 * ele aceita para a intervenção errada.</p>
 *
 * <p>O {@code skuRef} é o do modelo, mas conferido contra as candidatas: escolher fora da lista é o
 * agente decidindo sozinho o que oferecer, e o Core recusa. Recusar aqui dá o motivo exato, em vez
 * de mandar ao Core algo que ele vai descartar.</p>
 *
 * <h2>Nada de número no envelope</h2>
 *
 * <p>Probabilidade, suporte, margem e disponibilidade são sobrescritos pelo Core, quaisquer que
 * sejam os enviados. Então não são enviados. O que o modelo escreve na frase é conferido número a
 * número do lado de lá.</p>
 */
final class SugestaoDeUpsell {

    static final String REASON = "quote-draft";
    static final String TOOL = "sugerir_itens";
    static final String TIPO = "suggestion";

    /** Chamar a tool, escolher e redigir — com folga para uma correção de argumento. */
    static final int MAX_ITERACOES = 4;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String SYSTEM_PROMPT = """
            Você é o agente US do VendaX. O vendedor acabou de montar uma cotação, e você sugere UM
            item que costuma vir junto — o card que aparece embaixo da cotação, com uma frase curta.

            O `payload` traz `pedidoEmMontagem` (os itens já na cotação) e `momentoDaConversa`.

            1. Chame `sugerir_itens` com:
               {"clienteRef": <clienteRef>, "vendedorRef": <vendedorRef>,
                "pedidoEmMontagem": <o do payload, como veio>,
                "momentoDaConversa": <o do payload, como veio>}
               Não invente itens nem mude o momento: os dois já vêm prontos.

            2. A tool devolve `sugestoes` (as candidatas que o Core já filtrou), cada uma com
               skuRef, descricao, argumento, probabilidade, suporte e margem.
               Se `sugestoes` vier vazia, responda apenas: SEM_SUGESTAO. Não invente item, não use o
               `intervencaoId` para criar uma sugestão e não chame a tool de novo.

            3. Escolha UMA sugestão e escreva o argumento — uma frase curta, do jeito que um vendedor
               falaria ao cliente.

            Regras da frase — o Core confere e descarta a frase que não cumprir:
            - No máximo 120 caracteres, sem markdown e sem emoji.
            - Você pode citar a probabilidade como porcentagem inteira (0,42 vira "42 %") e o
              suporte (quantos pedidos). NENHUM outro número: nada de preço, margem, desconto ou
              quantidade que você não tenha recebido.
            - Não prometa prazo, estoque nem condição comercial.
            - Fale do item, não do cliente ("costuma vir junto", "sai na maioria dos pedidos").

            Responda APENAS com um JSON, sem texto em volta e sem cercas de código:
            {"skuRef": "<o skuRef da sugestão escolhida>", "argumento": "<a frase>"}
            """;

    private SugestaoDeUpsell() {
    }

    /**
     * O rich object {@code suggestion}, ou {@code ERROR} quando não há o que sugerir.
     *
     * <p>"Nada elegível" não é falha do agente: o Core já filtrou, e o {@code motivo} explica. Sai
     * como {@code ERROR} curto porque é o contrato — a mensagem não nasce.</p>
     */
    static VendaxResult resultado(VendaxInvoke invoke, McpAgentRunner.Result result) {
        McpAgentRunner.ToolCall chamada = result.lastSuccessfulCall(TOOL);
        if (chamada == null) {
            return erro(invoke, "O US não consultou " + TOOL);
        }
        JsonNode daTool;
        try {
            daTool = MAPPER.readTree(chamada.resultText());
        } catch (Exception e) {
            return erro(invoke, TOOL + " devolveu algo que não é JSON");
        }

        // Grupo de controle (ADR-023): a intervenção existe e NÃO deve ser exibida. A tool já
        // devolve sugestões vazias nesse caso; dizer isso no erro poupa uma investigação.
        if (daTool.path("grupoControle").asBoolean(false)) {
            return erro(invoke, "Intervenção em grupo de controle: sem sugestão a exibir");
        }
        List<String> candidatas = skusDe(daTool);
        if (candidatas.isEmpty()) {
            String motivo = daTool.path("motivo").isTextual() ? daTool.path("motivo").asText() : null;
            return erro(invoke, motivo == null
                    ? "Nenhuma sugestão elegível"
                    : "Nenhuma sugestão elegível: " + motivo);
        }
        String intervencaoId = daTool.path("intervencaoId").isTextual()
                ? daTool.path("intervencaoId").asText() : null;
        if (intervencaoId == null || intervencaoId.isBlank()) {
            return erro(invoke, TOOL + " devolveu sugestões sem intervencaoId");
        }

        JsonNode escolha = escolhaDoModelo(result.finalText());
        if (escolha == null) {
            return erro(invoke, "O US não devolveu a escolha em JSON");
        }
        String skuRef = escolha.path("skuRef").asText("").strip();
        String argumento = escolha.path("argumento").asText("").strip();
        if (!candidatas.contains(skuRef)) {
            // Escolher fora da lista é o agente decidindo sozinho o que oferecer. O Core recusaria;
            // recusar aqui diz exatamente o que aconteceu.
            return erro(invoke, "O US escolheu um item fora das candidatas: "
                    + (skuRef.isBlank() ? "(nenhum)" : skuRef));
        }
        if (argumento.isBlank()) {
            return erro(invoke, "O US escolheu " + skuRef + " e não escreveu o argumento");
        }

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("intervencaoId", intervencaoId);
        payload.put("skuRef", skuRef);
        payload.put("argumento", argumento);
        ObjectNode rich = MAPPER.createObjectNode();
        rich.put("type", TIPO);
        rich.put("schemaVersion", VendaxResult.SCHEMA_VERSION);
        rich.set("payload", payload);
        try {
            return VendaxResult.ok(invoke, TIPO, MAPPER.writeValueAsString(rich)).comChave(chave(invoke));
        } catch (Exception e) {
            return erro(invoke, "Falha ao serializar a sugestão: " + e.getMessage());
        }
    }

    /** Falha do US também sai com a chave do acionamento — é por ela que o Core a casa. */
    private static VendaxResult erro(VendaxInvoke invoke, String mensagem) {
        return VendaxResult.error(invoke, mensagem).comChave(chave(invoke));
    }

    /**
     * A chave que o Core espera: {@code us:<traceId>}.
     *
     * <p>É por ela que o Core casa a sugestão com o acionamento; a derivação padrão (agente +
     * mensagem de origem) daria outra coisa. Sem {@code traceId}, cai na padrão — melhor uma chave
     * derivada do que nenhuma.</p>
     */
    static String chave(VendaxInvoke invoke) {
        return invoke.traceId() == null || invoke.traceId().isBlank()
                ? VendaxResult.idempotencyKeyOf(invoke)
                : "us:" + invoke.traceId();
    }

    /** Os skuRef que a tool ofereceu — a lista contra a qual a escolha é conferida. */
    private static List<String> skusDe(JsonNode daTool) {
        List<String> skus = new ArrayList<>();
        for (JsonNode sugestao : daTool.path("sugestoes")) {
            String sku = sugestao.path("skuRef").asText("").strip();
            if (!sku.isBlank()) {
                skus.add(sku);
            }
        }
        return skus;
    }

    private static JsonNode escolhaDoModelo(String texto) {
        String json = VendaxAgentDispatcher.extractJson(texto);
        if (json == null) {
            return null;
        }
        try {
            JsonNode no = MAPPER.readTree(json);
            return no.isObject() ? no : null;
        } catch (Exception e) {
            return null;
        }
    }
}
