package br.com.archflow.api.agent.vendax;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Um fluxo que termina num nó de decisão devolve a decisão ao Core (pedido do VendaX de 14/10/2026,
 * VEN-221).
 *
 * <p>Antes, a saída do nó — mapa com {@code route} e {@code answers}, sem {@code text} — era
 * reduzida a nada, e o Core recebia "o fluxo não devolveu um JSON". A decisão existia e era
 * descartada no último salto.</p>
 */
@DisplayName("a decisão no result do Core")
class DecisaoNoResultTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A saída do nó como o {@code DecisionComponent} a grava — chaves e tipos iguais aos dele. */
    private static Map<String, Object> saidaDoNo() {
        Map<String, Object> intencao = new LinkedHashMap<>();
        intencao.put("value", "PEDIDO");
        intencao.put("confidence", 0.97);
        intencao.put("route", "AUTO");
        intencao.put("type", "choice");
        intencao.put("choice", "PEDIDO");
        intencao.put("probabilities", Map.of("PEDIDO", 0.97, "DUVIDA", 0.03));
        Map<String, Object> urgente = new LinkedHashMap<>();
        urgente.put("value", true);
        urgente.put("confidence", 0.6);
        urgente.put("route", "REVIEW");
        urgente.put("type", "noul");
        urgente.put("probability", 0.8);
        urgente.put("result", true);
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("intencao", intencao);
        answers.put("urgente", urgente);

        Map<String, Object> saida = new LinkedHashMap<>();
        saida.put("route", "AUTO");
        saida.put("confidence", 0.97);
        saida.put("decision", "PEDIDO");
        saida.put("primary", "intencao");
        saida.put("answers", answers);
        saida.put("provider", "http-decisions");
        saida.put("model", "typesafe/jev-1.14-20261014");
        saida.put("calibrated", true);
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("inputTokens", 312L);
        usage.put("outputTokens", 9L);
        usage.put("costUsd", 0.00002);
        saida.put("usage", usage);
        return saida;
    }

    @Test
    @DisplayName("a forma pedida: por pergunta valor, confiança e rota; por chamada modelo e uso em USD")
    void forma() throws Exception {
        JsonNode e = MAPPER.readTree(DecisaoNoResult.envelope(saidaDoNo()));

        JsonNode intencao = e.path("decisao").path("intencao");
        assertThat(intencao.path("valor").asText()).isEqualTo("PEDIDO");
        assertThat(intencao.path("confianca").asDouble()).isEqualTo(0.97);
        assertThat(intencao.path("rota").asText()).isEqualTo("AUTO");
        assertThat(intencao.path("tipo").asText()).isEqualTo("choice");
        assertThat(intencao.path("probabilidades").path("DUVIDA").asDouble()).isEqualTo(0.03);

        // A pergunta incerta mostra a SUA rota; a do nó continua AUTO porque ela não está no gate.
        assertThat(e.path("decisao").path("urgente").path("rota").asText()).isEqualTo("REVIEW");
        assertThat(e.path("decisao").path("urgente").path("valor").asBoolean()).isTrue();
        assertThat(e.path("rota").asText()).isEqualTo("AUTO");
        assertThat(e.path("confianca").asDouble()).isEqualTo(0.97);
        assertThat(e.path("principal").asText()).isEqualTo("intencao");

        assertThat(e.path("modelo").asText()).as("como o provedor respondeu, com a versão")
                .isEqualTo("typesafe/jev-1.14-20261014");
        assertThat(e.path("provedor").asText()).isEqualTo("http-decisions");
        assertThat(e.path("uso").path("entrada").asLong()).isEqualTo(312);
        assertThat(e.path("uso").path("saida").asLong()).isEqualTo(9);
        assertThat(e.path("uso").path("custoUsd").asDouble()).isEqualTo(0.00002);
        assertThat(e.has("erro")).isFalse();
    }

    /** O que o nó não informou fica ausente — nunca zero, nunca vazio. */
    @Test
    @DisplayName("custo não declarado e modelo desconhecido ficam ausentes")
    void ausenciaEhAusencia() throws Exception {
        Map<String, Object> saida = saidaDoNo();
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("inputTokens", 10L);
        usage.put("outputTokens", 2L);
        usage.put("costUsd", null);
        saida.put("usage", usage);
        saida.remove("model");

        JsonNode e = MAPPER.readTree(DecisaoNoResult.envelope(saida));
        assertThat(e.path("uso").has("custoUsd")).isFalse();
        assertThat(e.path("uso").path("entrada").asLong()).isEqualTo(10);
        assertThat(e.has("modelo")).isFalse();
    }

    /** Falha nunca vira palpite: a escalada chega com o motivo e sem resposta. */
    @Test
    @DisplayName("decisão que não saiu: rota ESCALATE, erro, e nenhuma pergunta")
    void degradada() throws Exception {
        Map<String, Object> saida = new LinkedHashMap<>();
        saida.put("route", "ESCALATE");
        saida.put("confidence", 0.0);
        saida.put("decision", null);
        saida.put("answers", Map.of());
        saida.put("error", "sem chave de API para o provedor 'http-decisions'");

        JsonNode e = MAPPER.readTree(DecisaoNoResult.envelope(saida));
        assertThat(e.path("rota").asText()).isEqualTo("ESCALATE");
        assertThat(e.path("erro").asText()).contains("sem chave");
        assertThat(e.path("decisao").isEmpty()).isTrue();
        assertThat(e.has("uso")).isFalse();
    }

    @Test
    @DisplayName("recusa: valor nulo explícito, para não se confundir com pergunta inexistente")
    void recusa() throws Exception {
        Map<String, Object> recusada = new LinkedHashMap<>();
        recusada.put("value", null);
        recusada.put("confidence", 0.0);
        recusada.put("route", "ESCALATE");
        recusada.put("type", "refused");
        Map<String, Object> saida = saidaDoNo();
        saida.put("answers", Map.of("intencao", recusada));

        JsonNode e = MAPPER.readTree(DecisaoNoResult.envelope(saida));
        assertThat(e.path("decisao").path("intencao").has("valor")).isTrue();
        assertThat(e.path("decisao").path("intencao").path("valor").isNull()).isTrue();
        assertThat(e.path("decisao").path("intencao").path("tipo").asText()).isEqualTo("refused");
    }

    @Test
    @DisplayName("só é decisão o que tem route E answers; o resto é recusado")
    void reconhecimento() {
        assertThat(DecisaoNoResult.ehDecisao(saidaDoNo())).isTrue();
        assertThat(DecisaoNoResult.ehDecisao(Map.of("text", "oi", "suspended", false))).isFalse();
        assertThat(DecisaoNoResult.ehDecisao(Map.of("route", "AUTO"))).isFalse();
        assertThat(DecisaoNoResult.ehDecisao(Map.of("answers", Map.of()))).isFalse();
        assertThat(DecisaoNoResult.ehDecisao(null)).isFalse();
        assertThatThrownBy(() -> DecisaoNoResult.envelope(Map.of("text", "oi")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Nested
    @DisplayName("no caminho de fluxo")
    class NoCaminhoDeFluxo {

        private final AgentFlowRunner runner = new AgentFlowRunner(null, null, null, null);

        private AgentFlowRunner.Saida reduzir(Object saida) throws Exception {
            for (Method m : AgentFlowRunner.class.getDeclaredMethods()) {
                if (m.getName().equals("reduzir")) {
                    m.setAccessible(true);
                    return (AgentFlowRunner.Saida) m.invoke(runner, saida);
                }
            }
            throw new AssertionError("reduzir sumiu");
        }

        /** O caso que motivou: antes, isto virava Saida(null) e um ERROR "não devolveu um JSON". */
        @Test
        @DisplayName("a saída do nó de decisão vira o texto da Saida — o envelope em JSON")
        void decisaoViraTexto() throws Exception {
            AgentFlowRunner.Saida s = reduzir(saidaDoNo());
            assertThat(s.suspenso()).isFalse();
            JsonNode e = MAPPER.readTree(s.texto());
            assertThat(e.path("decisao").path("intencao").path("valor").asText()).isEqualTo("PEDIDO");
            assertThat(e.path("rota").asText()).isEqualTo("AUTO");
        }

        @Test
        @DisplayName("a saída de um mcp-agent segue como antes")
        void agenteSegueIgual() throws Exception {
            AgentFlowRunner.Saida s = reduzir(Map.of("text", "{\"a\":1}", "suspended", false));
            assertThat(s.texto()).isEqualTo("{\"a\":1}");
        }

        @Test
        @DisplayName("no dispatcher, com saidaSchema decisao@1, o rich object é a decisão tipada")
        void noDispatcher() throws Exception {
            AgentFlowRunner fluxo = mock(AgentFlowRunner.class);
            VendaxAgentDispatcher dispatcher = new VendaxAgentDispatcher(
                    mock(br.com.archflow.api.agent.qp.QpAgentService.class),
                    mock(br.com.archflow.api.agent.mcp.McpAgentRunner.class),
                    mock(br.com.archflow.api.mcp.vendax.VendaxMcpClientProvider.class),
                    mock(VendaxResultSender.class), Executors.newSingleThreadExecutor(), null, fluxo);
            Map<String, Object> documento = Map.of("id", "triagem",
                    "steps", List.of(Map.of("id", "intencao", "type", "decision")));
            VendaxInvoke invoke = new VendaxInvoke("1.0", "t1", "c1", "triagem", "m1", "quero 10 cx",
                    "LIGHT", "teste", "trace", null, null, null, "k1", null,
                    new DefinicaoDeAgente("FLUXO", documento, null, "decisao@1",
                            List.of(), Map.of(), Map.of(), "triagem@1"));
            when(fluxo.executar(any(), any(), any(), any(), any(), anyBoolean()))
                    .thenReturn(reduzir(saidaDoNo()));

            var feito = dispatcher.executarEmFluxo(invoke,
                    br.com.archflow.api.agent.mcp.SaidaDeTexto.soCancelamento(
                            new java.util.concurrent.CompletableFuture<>()),
                    new br.com.archflow.api.agent.mcp.ContadorDeUso());

            assertThat(feito.resultado().status()).isEqualTo(VendaxResult.OK);
            assertThat(feito.resultado().richObjectType()).isEqualTo("decisao");
            JsonNode rich = MAPPER.readTree(feito.resultado().richObject());
            assertThat(rich.path("decisao").path("intencao").path("rota").asText()).isEqualTo("AUTO");
            assertThat(rich.path("modelo").asText()).isEqualTo("typesafe/jev-1.14-20261014");
        }
    }
}
