package br.com.archflow.api.agent.vendax;

import br.com.archflow.api.agent.mcp.McpAgentRunner;
import br.com.archflow.api.agent.qp.QpAgentService;
import br.com.archflow.api.flow.WorkflowDeserializer;
import br.com.archflow.api.mcp.vendax.VendaxMcpClientProvider;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A saída do agente como objeto JSON: documento inteiro, cerca externa e, desde o VEN-1132, o
 * {@code <span>} vazio fora de string. Cada regra tem o par "deve" / "não deve".
 */
@DisplayName("JsonDeSaida — o que é aceito como objeto")
class JsonDeSaidaTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A saída real, gravada pela bancada do Core em 15/11 (gemini-3.5-flash-lite, ns@6). */
    private static final String COM_SPAN = "{\"parametro\": {\"descontoPedidoPb\": null,<span> </span>"
            + "\"caixas\": 20}, \"resposta\": \"o teto do seu passo sem escalar é 6% para as 20 caixas do "
            + "pedido.\"}";

    private static Map<?, ?> ler(String json) throws Exception {
        return JSON.readValue(json, Map.class);
    }

    @Nested
    @DisplayName("span vazio fora de string")
    class SpanVazio {

        @Test
        @DisplayName("deve: o exemplo medido vira objeto, com caixas = 20")
        void exemploMedido() throws Exception {
            String s = JsonDeSaida.objeto(COM_SPAN);

            assertThat(s).isNotNull().doesNotContain("<span>");
            assertThat(((Map<?, ?>) ler(s).get("parametro")).get("caixas")).isEqualTo(20);
        }

        @Test
        @DisplayName("deve: <span></span> e <span>\\n</span> também")
        void variantesVazias() {
            assertThat(JsonDeSaida.objeto("{\"a\": 1,<span></span>\"b\": 2}")).isEqualTo("{\"a\": 1,\"b\": 2}");
            assertThat(JsonDeSaida.objeto("{\"a\": 1,<span>\n</span>\"b\": 2}")).isEqualTo("{\"a\": 1,\"b\": 2}");
        }

        @Test
        @DisplayName("não deve: span dentro de string fica intacto")
        void dentroDeString() throws Exception {
            String entrada = "{\"resposta\": \"a <span> </span> fica\"}";

            String s = JsonDeSaida.objeto(entrada);

            assertThat(s).isEqualTo(entrada);
            assertThat(ler(s).get("resposta")).isEqualTo("a <span> </span> fica");
        }

        @Test
        @DisplayName("não deve: dentro de string com aspa escapada antes, o span continua sendo texto")
        void dentroDeStringComEscape() throws Exception {
            String entrada = "{\"r\": \"ele disse \\\"<span> </span>\\\" e\",<span> </span>\"b\": 1}";

            String s = JsonDeSaida.objeto(entrada);

            assertThat(s).isNotNull();
            assertThat(ler(s).get("r")).isEqualTo("ele disse \"<span> </span>\" e");
            assertThat(ler(s).get("b")).isEqualTo(1);
        }

        @Test
        @DisplayName("não deve: span com conteúdo")
        void comConteudo() {
            assertThat(JsonDeSaida.objeto("{\"a\": 1,<span>x</span>\"b\": 2}")).isNull();
        }

        @Test
        @DisplayName("não deve: outra tag")
        void outraTag() {
            assertThat(JsonDeSaida.objeto("{\"a\": 1,<b> </b>\"b\": 2}")).isNull();
            assertThat(JsonDeSaida.objeto("{\"a\": 1,<span class=\"x\"> </span>\"b\": 2}")).isNull();
        }

        @Test
        @DisplayName("não deve: JSON quebrado por outro motivo")
        void quebradoPorOutroMotivo() {
            assertThat(JsonDeSaida.objeto("{\"a\": 1 \"b\": 2}")).isNull();
            assertThat(JsonDeSaida.objeto("{\"a\": 1 <span> </span>\"b\": 2}"))
                    .as("remover o span não conserta a vírgula que falta").isNull();
        }

        @Test
        @DisplayName("não deve: o span não abre caminho para trailing tokens nem para não-objeto")
        void mesmasRegras() {
            assertThat(JsonDeSaida.objeto("{\"a\": 1}<span> </span>{\"b\": 2}")).isNull();
            assertThat(JsonDeSaida.objeto("[1,<span> </span>2]")).isNull();
            assertThat(JsonDeSaida.objeto("{\"a\": 1,<span> </span>\"a\": 2}")).isNull();
        }
    }

    @Nested
    @DisplayName("cerca externa")
    class Cerca {

        @Test
        @DisplayName("deve: a cerca de hoje, sem span")
        void semSpan() {
            assertThat(JsonDeSaida.objeto("```json\n{\"a\": 1}\n```")).isEqualTo("{\"a\": 1}");
        }

        @Test
        @DisplayName("deve: a cerca com o span dentro")
        void comSpan() {
            assertThat(JsonDeSaida.objeto("```json\n{\"a\": 1,<span> </span>\"b\": 2}\n```"))
                    .isEqualTo("{\"a\": 1,\"b\": 2}");
        }

        @Test
        @DisplayName("não deve: texto em volta da cerca")
        void textoEmVolta() {
            assertThat(JsonDeSaida.objeto("Aqui:\n```json\n{\"a\": 1}\n```")).isNull();
        }
    }

    @Nested
    @DisplayName("no dispatcher")
    class NoDispatcher {

        private final VendaxAgentDispatcherTest.CapturingSender sender =
                new VendaxAgentDispatcherTest.CapturingSender();

        private VendaxAgentDispatcher dispatcherQueDevolve(String texto) {
            WorkflowDeserializer deserializer = mock(WorkflowDeserializer.class);
            FlowEngine engine = mock(FlowEngine.class);
            @SuppressWarnings("unchecked")
            ObjectProvider<FlowEngine> provider = mock(ObjectProvider.class);
            when(provider.getObject()).thenReturn(engine);
            when(deserializer.toFlow(any())).thenReturn(mock(Flow.class));
            FlowResult resultado = new FlowResult() {
                @Override public Optional<Object> getOutput() { return Optional.of(Map.of("text", texto)); }
                @Override public ExecutionStatus getStatus() { return ExecutionStatus.COMPLETED; }
                @Override public List<br.com.archflow.model.error.ExecutionError> getErrors() { return List.of(); }
                @Override public br.com.archflow.model.engine.ExecutionMetrics getMetrics() { return null; }
            };
            when(engine.execute(any(), any())).thenReturn(CompletableFuture.completedFuture(resultado));
            AgentFlowRunner fluxo = new AgentFlowRunner(deserializer, provider, mock(FlowRepository.class), null);
            return new VendaxAgentDispatcher(mock(QpAgentService.class), mock(McpAgentRunner.class),
                    mock(VendaxMcpClientProvider.class), sender, mock(ExecutorService.class), null, fluxo);
        }

        private static VendaxInvoke invoke() {
            return new VendaxInvoke("1.0", "t1", "c1", "NS", "m1", "x", "LIGHT", "teto?", "trace",
                    null, "cli-1", "vend-1", "chave-NS", null, null,
                    new DefinicaoDeAgente("FLUXO", Map.of("steps", List.of()), null, "ns-consulta@1",
                            List.of(), Map.of(), Map.of(), "ns@6"));
        }

        @Test
        @DisplayName("a saída medida vira OK com o objeto, e não ERROR")
        void spanViraOk() throws Exception {
            dispatcherQueDevolve(COM_SPAN).runAndReport(invoke());

            assertThat(sender.sent).singleElement().satisfies(r -> {
                assertThat(r.status()).isEqualTo(VendaxResult.OK);
                assertThat(r.richObject()).doesNotContain("<span>");
                assertThat(((Map<?, ?>) ler(r.richObject()).get("parametro")).get("caixas")).isEqualTo(20);
            });
        }

        @Test
        @DisplayName("span com conteúdo continua ERROR, com o outputText")
        void spanComConteudoContinuaErro() {
            String texto = "{\"a\": 1,<span>x</span>\"b\": 2}";

            dispatcherQueDevolve(texto).runAndReport(invoke());

            assertThat(sender.sent).singleElement().satisfies(r -> {
                assertThat(r.status()).isEqualTo(VendaxResult.ERROR);
                assertThat(r.error()).contains("não devolveu um JSON");
                assertThat(r.outputText()).as("o texto vai como veio, sem normalização").isEqualTo(texto);
            });
        }
    }
}
