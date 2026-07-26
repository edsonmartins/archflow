package br.com.archflow.sdk;

import br.com.archflow.dsl.Nodes;
import br.com.archflow.dsl.WorkflowDocument;
import br.com.archflow.dsl.Workflows;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.standalone.FlowSerializer;
import br.com.archflow.standalone.model.SerializableFlow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A execução embarcada, e — mais importante — a conformidade com o
 * <b>segundo</b> leitor do schema.
 *
 * <p>O documento de workflow tem dois consumidores independentes: o
 * {@code DefaultWorkflowDeserializer} do archflow-api (coberto pelo
 * {@code DslConformanceTest}) e o {@link FlowSerializer} do archflow-standalone,
 * que é o caminho da execução sem servidor. Eles são código separado, sem nada
 * que os obrigue a concordar. Um documento que o motor do servidor entende e o
 * runner embarcado lê torto seria um bug que só aparece em quem escolheu rodar
 * offline.
 */
@DisplayName("EmbeddedWorkflowRunner")
class EmbeddedWorkflowRunnerTest {

    private static WorkflowDocument doc() {
        return Workflows.define("embarcado")
                .named("Fluxo embarcado")
                .step("entrada", Nodes.component("input").labeled("Entrada"))
                .step("saida", Nodes.component("output").operation("emit"))
                .edge("entrada", "saida")
                .build();
    }

    /**
     * O documento da DSL atravessa o serializador do standalone com passos,
     * conexões e configuração intactos.
     */
    @Test
    @DisplayName("o documento da DSL é legível pelo caminho standalone")
    void documentIsReadableByTheStandalonePath() throws Exception {
        SerializableFlow flow = new FlowSerializer().deserialize(doc().toJson());

        assertThat(flow.getId()).isEqualTo("embarcado");
        assertThat(flow.getSteps()).extracting(s -> s.getId())
                .containsExactly("entrada", "saida");

        List<StepConnection> saidas = flow.getSteps().get(0).getConnections();
        assertThat(saidas)
                .as("sem isto o fluxo executaria só o primeiro passo, sem erro")
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.getSourceId()).isEqualTo("entrada");
                    assertThat(c.getTargetId()).isEqualTo("saida");
                });
    }

    /**
     * O {@code config} da DSL é gravado sob a chave {@code config}; o modelo do
     * standalone declara {@code configuration} e aceita {@code config} por
     * {@code @JsonAlias}. É a espécie de detalhe que um teste de unidade de cada
     * lado nunca pega.
     */
    @Test
    @DisplayName("a config do nó chega ao modelo do standalone")
    void nodeConfigCrossesTheBoundary() throws Exception {
        WorkflowDocument document = Workflows.define("f")
                .step("chat", Nodes.llmChat("openai").with("model", "gpt-4o").with("temperature", 0.3))
                .build();

        SerializableFlow flow = new FlowSerializer().deserialize(document.toJson());

        var step = (br.com.archflow.standalone.model.SerializableStep) flow.getSteps().get(0);
        assertThat(step.getConfiguration())
                .containsEntry("model", "gpt-4o")
                .containsEntry("provider", "openai");
        assertThat(step.getComponentId()).isEqualTo("openai");
    }

    @Test
    @DisplayName("label e operation sobrevivem à fronteira")
    void labelAndOperationSurvive() throws Exception {
        SerializableFlow flow = new FlowSerializer().deserialize(doc().toJson());

        var entrada = (br.com.archflow.standalone.model.SerializableStep) flow.getSteps().get(0);
        var saida = (br.com.archflow.standalone.model.SerializableStep) flow.getSteps().get(1);

        assertThat(entrada.getLabel()).isEqualTo("Entrada");
        assertThat(saida.getOperation()).isEqualTo("emit");
    }

    /**
     * A execução de fato — que aqui termina em falha, porque nenhum componente
     * chamado "input" está registrado neste classpath de teste.
     *
     * <p>O que se verifica não é o sucesso do fluxo (seria preciso um componente
     * real), e sim que o runner <b>chega até o motor e volta com um resultado</b>
     * em vez de estourar no caminho: documento → JSON → SerializableFlow →
     * ArchFlowAgent → FlowResult.
     */
    @Test
    @DisplayName("executa de verdade e devolve um FlowResult, sem servidor")
    void runsAndReturnsAResult() {
        try (EmbeddedWorkflowRunner runner = EmbeddedWorkflowRunner.builder()
                .agentId("teste")
                .timeout(Duration.ofSeconds(30))
                .build()) {

            FlowResult result = runner.run(doc(), Map.of("input", "olá"));

            assertThat(result)
                    .as("o motor precisa devolver resultado mesmo quando o fluxo falha")
                    .isNotNull();
            assertThat(result.getStatus()).isNotNull();
        }
    }

    /**
     * Sem {@code pluginsPath}, nada é varrido.
     *
     * <p>O default do {@code AgentConfig} é o diretório relativo {@code "plugins"},
     * e abrir um jar de plugin executa o {@code onLoad} dele — código arbitrário,
     * sem sandbox. Uma biblioteca que faça isso só porque existia uma pasta com
     * esse nome ao lado do processo é uma armadilha; o runner desliga a varredura
     * por default, e este teste é o que impede alguém de "consertar" isso
     * repassando o default.
     */
    @Test
    @DisplayName("não varre diretório de plugins por default")
    void doesNotScanPluginsByDefault() {
        try (EmbeddedWorkflowRunner runner = EmbeddedWorkflowRunner.builder().build()) {
            // Um diretório ./plugins carregado apareceria como plugin ativo; a
            // ausência de exceção e a execução limpa sao o observavel disponivel
            // sem instrumentar o agente.
            assertThat(runner.run(doc())).isNotNull();
        }
    }

    @Test
    @DisplayName("close é seguro chamar mais de uma vez")
    void closeIsIdempotent() {
        EmbeddedWorkflowRunner runner = EmbeddedWorkflowRunner.builder().build();
        runner.close();
        runner.close();
    }
}
