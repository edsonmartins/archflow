package br.com.archflow.api.flow;

import br.com.archflow.agent.config.AgentConfig;
import br.com.archflow.agent.execution.DefaultFlowExecutor;
import br.com.archflow.agent.metrics.MetricsCollector;
import br.com.archflow.api.agent.vendax.AgentFlowRunner;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowMetadata;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.model.flow.StepError;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.model.flow.StepType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O motivo da falha atravessa os dois saltos, do passo até quem chamou o fluxo.
 *
 * <h2>O que motivou</h2>
 *
 * <p>Medido em 07–08/08: <b>toda</b> falha chegou ao Core como
 * {@code "Fluxo falhou: sem erro registrado"}. Quatro causas distintas, o mesmo
 * texto inútil:
 *
 * <pre>
 * {"error":{"message":"Provider returned error","code":400,
 *           "provider_name":"Amazon Bedrock","raw":"...did not allow prompt caching"}}
 * IllegalStateException: mcp-agent: o passo exige saída por tool [...]
 * java.io.IOException: HTTP/1.1 header parser received no bytes
 * java.net.ConnectException
 * </pre>
 *
 * <p>E isso <b>apesar</b> de a correção do segundo salto já estar em produção: o
 * {@code AgentFlowRunner} lê o erro do passo do contexto quando o fluxo não
 * registra nenhum. O que ele encontrava lá era uma lista vazia.
 *
 * <h2>Onde exatamente se perdia</h2>
 *
 * <p>{@link SimpleStepResult#failed} guardava a mensagem em {@code output} e
 * devolvia {@code errors = List.of()} — com um comentário dizendo que era de
 * propósito. {@code DefaultFlowExecutor.handleFailure} grava no contexto o que
 * {@code getErrors()} devolve. Os dois lados estavam internamente coerentes e
 * <b>discordavam entre si</b>: um escrevia em {@code output}, o outro lia
 * {@code errors}.
 *
 * <h2>Por que este teste roda o executor de verdade</h2>
 *
 * <p>Os testes que já existiam de cada lado passavam. O do executor
 * ({@code DefaultFlowExecutorTest}) montava o próprio {@code StepResult}, com
 * lista de erros preenchida à mão — nunca tocou {@code SimpleStepResult}. O do
 * chamador ({@code ErroDoPassoChegaAoCoreTest}) partia de um contexto já
 * populado com um {@code StepError}. Nenhum dos dois cruzava a costura onde o
 * defeito morava, e por isso o defeito sobreviveu aos dois.
 *
 * <p>Aqui o passo devolve um {@link SimpleStepResult} de verdade, o executor de
 * verdade o processa, e o texto é conferido no ponto em que o Core o receberia.
 */
@DisplayName("o motivo da falha chega inteiro ao chamador")
class MotivoDaFalhaChegaInteiroTest {

    private static final String MOTIVO =
            "mcp-agent: o passo exige saída por tool [montar_cotacao] e nenhuma foi chamada "
                    + "com sucesso. O modelo respondeu: \"(nada)\"";

    @Nested
    @DisplayName("na origem: quem escreve e quem lê param de discordar")
    class Origem {

        @Test
        @DisplayName("falha com mensagem preenche getErrors(), não só getOutput()")
        void erroNaOrigem() {
            SimpleStepResult r = SimpleStepResult.failed("cota", MOTIVO, 12);

            assertThat(r.getErrors())
                    .as("handleFailure grava no contexto o que getErrors() devolve; "
                            + "vazio aqui e o diagnostico morre no primeiro salto")
                    .hasSize(1);
            assertThat(r.getErrors().get(0).message()).isEqualTo(MOTIVO);
            assertThat(r.getOutput())
                    .as("quem ja lia de output nao pode quebrar")
                    .contains(MOTIVO);
        }

        /**
         * Falha sem mensagem nenhuma segue sem erro: inventar texto apagaria a
         * diferença entre "falhou sem dizer" e "falhou dizendo isto".
         */
        @Test
        @DisplayName("falha sem mensagem não ganha erro inventado")
        void semMensagemNaoInventa() {
            assertThat(SimpleStepResult.failed("cota", null, 1).getErrors()).isEmpty();
            assertThat(SimpleStepResult.failed("cota", "  ", 1).getErrors()).isEmpty();
        }

        @Test
        @DisplayName("sucesso continua sem erro")
        void sucessoSemErro() {
            assertThat(SimpleStepResult.ok("cota", "saida", 1).getErrors()).isEmpty();
        }
    }

    @Nested
    @DisplayName("primeiro salto: do passo ao contexto, pelo executor de verdade")
    class PrimeiroSalto {

        @Test
        @DisplayName("o executor grava o motivo em step.<id>.error")
        void executorGravaOMotivo() {
            DefaultFlowExecutor executor = new DefaultFlowExecutor(
                    Thread.currentThread().getContextClassLoader(),
                    new MetricsCollector(AgentConfig.builder().build()));
            ExecutionContext ctx = contexto();

            FlowResult resultado = executor.execute(
                    fluxo(passoQueFalhaCom(MOTIVO)), ctx);

            assertThat(resultado.getStatus()).isEqualTo(ExecutionStatus.FAILED);
            assertThat(ctx.get("step.cota.error"))
                    .as("e daqui que o segundo salto le o motivo")
                    .isPresent();
            assertThat(mensagemDe(ctx.get("step.cota.error").orElseThrow()))
                    .isEqualTo(MOTIVO);
        }
    }

    @Nested
    @DisplayName("segundo salto: do contexto ao texto que o Core recebe")
    class SegundoSalto {

        /**
         * O caminho inteiro, sem atalho: passo real → executor real → o método que
         * monta o texto de {@code "Fluxo falhou: X"}. Antes da correção este teste
         * terminava em {@code "sem erro registrado"}.
         */
        @Test
        @DisplayName("o texto do passo é o que chega, e não 'sem erro registrado'")
        void chegaInteiro() throws Exception {
            DefaultFlowExecutor executor = new DefaultFlowExecutor(
                    Thread.currentThread().getContextClassLoader(),
                    new MetricsCollector(AgentConfig.builder().build()));
            ExecutionContext ctx = contexto();

            FlowResult resultado = executor.execute(fluxo(passoQueFalhaCom(MOTIVO)), ctx);

            assertThat(errosDe(resultado, ctx))
                    .as("e este texto que o vendedor tem chance de entender; "
                            + "'sem erro registrado' custou uma semana de SSH")
                    .isEqualTo(MOTIVO);
        }
    }

    // ── helpers ─────────────────────────────────────────────────

    /** Invoca o {@code errosDe} do runner — o mesmo ponto que monta "Fluxo falhou: X". */
    private String errosDe(FlowResult resultado, ExecutionContext contexto) throws Exception {
        AgentFlowRunner runner = new AgentFlowRunner(null, null, null, null);
        for (Method m : AgentFlowRunner.class.getDeclaredMethods()) {
            if (m.getName().equals("errosDe")) {
                m.setAccessible(true);
                return (String) m.invoke(runner, resultado, contexto);
            }
        }
        throw new AssertionError("errosDe sumiu");
    }

    private static String mensagemDe(Object valor) {
        if (valor instanceof List<?> lista && !lista.isEmpty()
                && lista.get(0) instanceof StepError erro) {
            return erro.message();
        }
        return String.valueOf(valor);
    }

    private static ExecutionContext contexto() {
        DefaultExecutionContext ctx =
                new DefaultExecutionContext("tenant-1", "user-1", "session-1", null);
        FlowState estado = new FlowState();
        estado.setFlowId("flow-cota");
        estado.setTenantId("tenant-1");
        ctx.setState(estado);
        return ctx;
    }

    /** Passo que falha por {@link SimpleStepResult} — a costura que ninguém cobria. */
    private static FlowStep passoQueFalhaCom(String motivo) {
        return new FlowStep() {
            @Override public String getId() { return "cota"; }
            @Override public StepType getType() { return StepType.TOOL; }
            @Override public List<StepConnection> getConnections() { return List.of(); }
            @Override public CompletableFuture<StepResult> execute(ExecutionContext context) {
                return CompletableFuture.completedFuture(
                        SimpleStepResult.failed("cota", motivo, 12));
            }
        };
    }

    private static Flow fluxo(FlowStep passo) {
        return new Flow() {
            @Override public String getId() { return "flow-cota"; }
            @Override public FlowMetadata getMetadata() { return null; }
            @Override public List<FlowStep> getSteps() { return List.of(passo); }
            @Override public br.com.archflow.model.config.FlowConfiguration getConfiguration() {
                return null;
            }
            @Override public void validate() { }
        };
    }
}
