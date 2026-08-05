package br.com.archflow.api.agent.vendax;

import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.ErrorType;
import br.com.archflow.model.flow.StepError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Uma falha que não se explica ao chamador é meia falha.
 *
 * <h2>O que motivou</h2>
 *
 * <p>Medido em 04/08: um passo falhou com {@code IllegalStateException} carregando o diagnóstico
 * exato — qual tool de saída faltou e o que o modelo respondeu — e o Core recebeu
 * <b>"Fluxo falhou: sem erro registrado"</b>. A informação existia e era descartada em dois saltos.</p>
 *
 * <p>A causa: os erros ficam em dois lugares. O {@code FlowResult} carrega os erros do FLUXO; o erro
 * de um PASSO é gravado pelo executor no contexto, sob {@code step.<id>.error}. Quem só olha o
 * primeiro não encontra o segundo — e o segundo é o que diz por que falhou.</p>
 *
 * <p>Sem isto, toda investigação começa por SSH no log do runtime.</p>
 */
@DisplayName("O erro do passo chega ao chamador")
class ErroDoPassoChegaAoCoreTest {

    private final AgentFlowRunner runner = new AgentFlowRunner(null, null, null, null);

    private String erros(Object resultado, ExecutionContext contexto) throws Exception {
        for (Method m : AgentFlowRunner.class.getDeclaredMethods()) {
            if (m.getName().equals("errosDe")) {
                m.setAccessible(true);
                return (String) m.invoke(runner, resultado, contexto);
            }
        }
        throw new AssertionError("errosDe sumiu");
    }

    private ExecutionContext contextoCom(String chave, Object valor) {
        ExecutionContext c = new DefaultExecutionContext("t-1", "vendax", "flow-1", null);
        c.set(chave, valor);
        return c;
    }

    /** O caso real de 04/08: o fluxo não registra erro, e o passo registra. */
    @Test
    @DisplayName("sem erro no fluxo, usa o erro do PASSO")
    void usaOErroDoPasso() throws Exception {
        String motivo = "mcp-agent: o passo exige saída por tool [montar_cotacao] "
                + "e nenhuma foi chamada com sucesso. O modelo respondeu: \"(nada)\"";
        ExecutionContext ctx = contextoCom("step.cota.error",
                List.of(StepError.of(ErrorType.EXECUTION, "STEP_EXECUTION_ERROR", motivo)));

        assertThat(erros(new FlowResultVazio(), ctx))
                .as("é este texto que o Core precisa receber, e não 'sem erro registrado'")
                .isEqualTo(motivo);
    }

    /** Depois de um checkpoint o valor volta desserializado, sem o tipo. */
    @Test
    @DisplayName("erro que perdeu o tipo no checkpoint ainda é lido")
    void erroDesserializado() throws Exception {
        ExecutionContext ctx = contextoCom("step.cota.error", List.of("falhou por X"));

        assertThat(erros(new FlowResultVazio(), ctx)).isEqualTo("falhou por X");
    }

    /** Variável que não é erro de passo não pode virar mensagem de falha. */
    @Test
    @DisplayName("outras variáveis do contexto são ignoradas")
    void ignoraOutrasVariaveis() throws Exception {
        ExecutionContext ctx = contextoCom("step.cota.output", List.of("uma saída qualquer"));

        assertThat(erros(new FlowResultVazio(), ctx)).isEqualTo("sem erro registrado");
    }

    /** Sem nada em lugar nenhum, o fallback continua — mas agora é a última opção, não a primeira. */
    @Test
    @DisplayName("sem erro em lugar nenhum, o fallback permanece")
    void fallbackPermanece() throws Exception {
        ExecutionContext ctx = new DefaultExecutionContext("t-1", "vendax", "flow-1", null);

        assertThat(erros(new FlowResultVazio(), ctx)).isEqualTo("sem erro registrado");
    }

    /** FlowResult sem erros — o caso que fazia o diagnóstico se perder. */
    private static class FlowResultVazio implements br.com.archflow.model.flow.FlowResult {
        @Override public java.util.Optional<Object> getOutput() { return java.util.Optional.empty(); }
        @Override public br.com.archflow.model.enums.ExecutionStatus getStatus() {
            return br.com.archflow.model.enums.ExecutionStatus.FAILED;
        }
        @Override public List<br.com.archflow.model.error.ExecutionError> getErrors() { return List.of(); }
        @Override public br.com.archflow.model.engine.ExecutionMetrics getMetrics() { return null; }
    }
}
