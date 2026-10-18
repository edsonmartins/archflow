package br.com.archflow.api.agent.vendax;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A saída de um nó {@code decision} na forma que vai ao Core quando ele é o <b>último passo</b> do
 * fluxo.
 *
 * <h2>Por que existe</h2>
 *
 * <p>Pedido do VendaX de 14/10/2026 (VEN-221, ADR-107): toda saída de agente que é escolha fechada
 * passa a sair de um nó de decisão, e o Core lê o resultado pelo envelope. Até aqui o caminho de
 * fluxo só sabia reduzir a saída de um {@code mcp-agent} ({@code text}) ou a entrada repassada
 * ({@code input}); um fluxo que terminasse num {@code decision} devolvia mapa sem {@code text} e o
 * Core recebia "o fluxo não devolveu um JSON" — um erro que não aconteceu.</p>
 *
 * <h2>A forma</h2>
 *
 * <pre>
 * {"decisao":   {"intencao": {"tipo": "choice", "valor": "PEDIDO", "confianca": 0.97, "rota": "AUTO"}},
 *  "rota":      "AUTO",            // a do nó: a MENOR confiança entre as perguntas do gate
 *  "confianca": 0.97,
 *  "principal": "intencao",
 *  "modelo":    "typesafe/jev-1.14-20261014",   // como o provedor respondeu, com a versão
 *  "provedor":  "http-decisions",
 *  "uso":       {"entrada": 312, "saida": 9, "custoUsd": 0.00002}}
 * </pre>
 *
 * <p>É a forma que o Core pediu, com os nomes que ele pediu. Por pergunta vai a rota <i>daquela</i>
 * pergunta (pelos limiares do nó) e, no topo, a do nó — as duas, porque uma pergunta informativa
 * fora do {@code gate} pode ser incerta sem rebaixar a decisão, e quem lê precisa ver a diferença.
 * {@code erro} só aparece quando a decisão não saiu (e aí {@code rota} é {@code ESCALATE}, sem
 * palpite). O que o nó não informou fica <b>ausente</b>, nunca zero nem vazio — {@code custoUsd}
 * ausente é "o provedor não declarou", não "de graça".</p>
 *
 * <h2>O que ela não é</h2>
 *
 * <p>Não é o consumo do result ({@code VendaxResult.uso}): aquele é da execução inteira e já soma a
 * decisão, porque o nó reporta ao {@code ContadorDeUso}. Este {@code uso} é o da chamada de decisão,
 * para quem compara modelos de decisão entre si.</p>
 */
final class DecisaoNoResult {

    private static final ObjectMapper JSON = new ObjectMapper();

    private DecisaoNoResult() {
    }

    /**
     * {@code true} quando o mapa é a saída de um nó de decisão: tem {@code route} e {@code answers}.
     * Um {@code mcp-agent} não grava nenhum dos dois; um componente qualquer poderia gravar um, não
     * os dois com esses nomes — e se gravar, está dizendo que decidiu.
     */
    static boolean ehDecisao(Map<?, ?> saida) {
        return saida != null && saida.get("route") instanceof String
                && saida.get("answers") instanceof Map<?, ?>;
    }

    /** O envelope em JSON; lança se o mapa não for a saída de um nó de decisão. */
    static String envelope(Map<?, ?> saida) {
        if (!ehDecisao(saida)) {
            throw new IllegalArgumentException("não é a saída de um nó de decisão: " + saida);
        }
        try {
            return JSON.writeValueAsString(montar(saida));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("não deu para serializar a decisão", e);
        }
    }

    private static Map<String, Object> montar(Map<?, ?> saida) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        Map<String, Object> decisao = new LinkedHashMap<>();
        ((Map<?, ?>) saida.get("answers")).forEach((id, resposta) -> {
            if (resposta instanceof Map<?, ?> r) {
                decisao.put(String.valueOf(id), pergunta(r));
            }
        });
        envelope.put("decisao", decisao);
        envelope.put("rota", saida.get("route"));
        porSeHouver(envelope, "confianca", saida.get("confidence"));
        porSeHouver(envelope, "principal", saida.get("primary"));
        porSeHouver(envelope, "modelo", saida.get("model"));
        porSeHouver(envelope, "provedor", saida.get("provider"));
        if (saida.get("usage") instanceof Map<?, ?> u) {
            Map<String, Object> uso = new LinkedHashMap<>();
            porSeHouver(uso, "entrada", u.get("inputTokens"));
            porSeHouver(uso, "saida", u.get("outputTokens"));
            porSeHouver(uso, "custoUsd", u.get("costUsd"));
            if (!uso.isEmpty()) {
                envelope.put("uso", uso);
            }
        }
        porSeHouver(envelope, "erro", saida.get("error"));
        return envelope;
    }

    private static Map<String, Object> pergunta(Map<?, ?> r) {
        Map<String, Object> p = new LinkedHashMap<>();
        porSeHouver(p, "tipo", r.get("type"));
        // `value` pode ser legitimamente nulo (recusa): vai explícito, para o Core não confundir
        // "respondeu nulo" com "não há esta pergunta".
        p.put("valor", r.get("value"));
        porSeHouver(p, "confianca", r.get("confidence"));
        porSeHouver(p, "rota", r.get("route"));
        porSeHouver(p, "probabilidades", r.get("probabilities"));
        return p;
    }

    private static void porSeHouver(Map<String, Object> alvo, String chave, Object valor) {
        if (valor != null) {
            alvo.put(chave, valor);
        }
    }
}
