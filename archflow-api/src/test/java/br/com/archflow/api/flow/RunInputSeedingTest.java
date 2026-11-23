package br.com.archflow.api.flow;

import br.com.archflow.api.agui.AgUiSeedInputAccess;
import br.com.archflow.api.agui.RunAgentInput;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Entrada da execução → variáveis do contexto")
class RunInputSeedingTest {

    private static ExecutionContext ctx() {
        return new DefaultExecutionContext(null, "t", "exec-1",
                MessageWindowChatMemory.builder().maxMessages(5).build());
    }

    @Test
    @DisplayName("objeto sem 'input': cada chave vira variável e o objeto inteiro vira o input")
    void objetoSemInputViraInput() {
        ExecutionContext ctx = ctx();
        Map<String, Object> entrada = Map.of("message", "meu boleto venceu");

        RunInputSeeding.seedVariables(ctx, entrada);

        assertThat(ctx.get("message")).contains("meu boleto venceu");
        assertThat(ctx.get("input")).contains(entrada);
    }

    @Test
    @DisplayName("'input' explícito vence: o texto chega como texto, não como o mapa")
    void inputExplicitoVence() {
        ExecutionContext ctx = ctx();

        RunInputSeeding.seedVariables(ctx, Map.of("input", "meu boleto venceu", "canal", "web"));

        assertThat(ctx.get("input")).contains("meu boleto venceu");
        assertThat(ctx.get("canal")).contains("web");
    }

    @Test
    @DisplayName("sem entrada, nada muda (nem input vazio)")
    void semEntrada() {
        ExecutionContext ctx = ctx();

        RunInputSeeding.seedVariables(ctx, null);

        assertThat(ctx.get("input")).isEmpty();
    }

    /** {} é o corpo padrão do MCP sem argumentos e do SDK — não pode virar a entrada "{}". */
    @Test
    @DisplayName("objeto vazio não vira input")
    void objetoVazioNaoViraInput() {
        ExecutionContext rest = ctx();
        ExecutionContext agUi = ctx();

        RunInputSeeding.seedVariables(rest, Map.of());
        AgUiSeedInputAccess.seed(agUi, new RunAgentInput(null, Map.of(), null, null, null, null));

        assertThat(rest.get("input")).isEmpty();
        assertThat(agUi.get("input")).isEmpty();
    }

    /**
     * O defeito: a mesma entrada dava resultados diferentes pelo Executar do editor (AG-UI) e pelo
     * Executar da lista / "Executar novamente" (REST). As duas portas têm de deixar o mesmo contexto.
     */
    @Test
    @DisplayName("AG-UI (state) e REST deixam o mesmo contexto para a mesma entrada")
    void agUiERestConcordam() {
        Map<String, Object> entrada = Map.of("message", "senha bloqueada");
        ExecutionContext rest = ctx();
        ExecutionContext agUi = ctx();

        RunInputSeeding.seedVariables(rest, entrada);
        AgUiSeedInputAccess.seed(agUi, new RunAgentInput(null, entrada, null, null, null, null));

        assertThat(agUi.get("input")).isEqualTo(rest.get("input"));
        assertThat(agUi.get("message")).isEqualTo(rest.get("message"));
    }

    @Test
    @DisplayName("AG-UI: state com 'input' explícito não é sobrescrito pelo mapa")
    void agUiRespeitaInputExplicito() {
        ExecutionContext ctx = ctx();

        AgUiSeedInputAccess.seed(ctx, new RunAgentInput(null, Map.of("input", "texto"), null, null, null, null));

        assertThat(ctx.get("input")).contains("texto");
    }

    @Test
    @DisplayName("AG-UI: a última mensagem continua sendo o input (texto livre)")
    void agUiMensagemViraInput() {
        ExecutionContext ctx = ctx();

        AgUiSeedInputAccess.seed(ctx, new RunAgentInput(
                List.of(Map.of("role", "user", "content", "meu boleto venceu")), null, null, null, null, null));

        assertThat(ctx.get("input")).contains("meu boleto venceu");
    }
}
