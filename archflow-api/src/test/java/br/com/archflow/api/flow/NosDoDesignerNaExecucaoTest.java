package br.com.archflow.api.flow;

import br.com.archflow.agent.persistence.InMemoryFlowRepository;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.exceptions.FlowValidationException;
import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.enums.StepStatus;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RCHFLW-11 no motor de verdade: Entrada e Saída executam como passagem, e um nó de tipo sem execução
 * recusa o fluxo no início, com uma mensagem que nomeia o tipo e o nó — antes os dois casos terminavam
 * em {@code component not found}, o segundo só depois de rodar os passos anteriores.
 */
@DisplayName("nós do designer na execução: passagem e tipo sem execução")
class NosDoDesignerNaExecucaoTest {

    private static ComponentCatalog emptyCatalog() {
        ComponentCatalog catalog = mock(ComponentCatalog.class);
        when(catalog.getComponent(anyString())).thenReturn(Optional.empty());
        return catalog;
    }

    private static DefaultFlowStepFactory factory(ComponentCatalog catalog) {
        return new DefaultFlowStepFactory(catalog, mock(DynamicWorkflowService.class),
                mock(br.com.archflow.agent.streaming.EventStreamRegistry.class),
                mock(br.com.archflow.engine.core.StateManager.class), null);
    }

    private static Flow flow(String id, List<Map<String, Object>> steps) {
        return new DefaultWorkflowDeserializer(factory(emptyCatalog()))
                .toFlow(Map.of("id", id, "metadata", Map.of("name", id), "steps", steps));
    }

    private static Map<String, Object> node(String id, String type, String label, String next) {
        return next == null
                ? Map.of("id", id, "type", type, "componentId", type, "label", label)
                : Map.of("id", id, "type", type, "componentId", type, "label", label,
                        "connections", List.of(Map.of("sourceId", id, "targetId", next)));
    }

    private static DefaultExecutionContext context(String flowId) {
        return new DefaultExecutionContext("t1", "u1", flowId,
                MessageWindowChatMemory.builder().maxMessages(5).build());
    }

    private static FlowEngine engine() {
        return FlowEngineFactory.create(new InMemoryFlowRepository(), null, null, new InMemoryStateManager());
    }

    @Test
    @DisplayName("Entrada → Saída termina COMPLETED e a Saída devolve a entrada do run")
    void entradaESaidaRepassamAEntrada() throws Exception {
        Flow flow = flow("exec-io", List.of(
                node("entrada", "input", "Entrada", "saida"),
                node("saida", "output", "Saída", null)));
        DefaultExecutionContext ctx = context(flow.getId());
        Map<String, Object> entrada = Map.of("message", "meu boleto venceu");
        ctx.set("input", entrada);

        FlowResult result = engine().execute(flow, ctx).get(15, TimeUnit.SECONDS);

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(flow.getSteps()).allSatisfy(s -> assertThat(s).isInstanceOf(PassThroughStep.class));
        // A saída de cada nó fica sob o id dele, como a de qualquer passo: ${saida} é a entrada do run.
        assertThat(ctx.get("entrada")).contains(entrada);
        assertThat(ctx.get("saida")).contains(entrada);
        assertThat(ctx.get("input")).contains(entrada);
    }

    @Test
    @DisplayName("a passagem devolve a entrada como saída do passo; sem entrada, não inventa uma")
    void passagemSemEntradaNaoFabricaValor() {
        PassThroughStep step = new PassThroughStep("saida", List.of());
        DefaultExecutionContext ctx = context("x");
        ctx.set("input", "texto do passo anterior");

        StepResult com = step.execute(ctx).join();
        StepResult sem = step.execute(context("y")).join();

        assertThat(com.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(com.getOutput()).contains("texto do passo anterior");
        assertThat(sem.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(sem.getOutput()).isEmpty();
    }

    @Test
    @DisplayName("nó de tipo sem execução recusa o fluxo antes do primeiro passo, nomeando tipo e nó")
    void tipoSemExecucaoFalhaNoInicio() {
        Flow flow = flow("exec-agent", List.of(
                node("entrada", "input", "Entrada", "agente"),
                node("agente", "agent", "Agente de vendas", "saida"),
                node("saida", "output", "Saída", null)));
        DefaultExecutionContext ctx = context(flow.getId());
        ctx.set("input", "oi");

        assertThatThrownBy(() -> engine().execute(flow, ctx).get(15, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .rootCause()
                .isInstanceOf(FlowValidationException.class)
                .hasMessageContaining("o tipo de nó 'agent' ainda não tem execução no servidor")
                .hasMessageContaining("\"Agente de vendas\" (agente)")
                .hasMessageNotContaining("component not found");
        // Nenhum passo rodou: a Entrada, que vem antes do agente, não deixou saída.
        assertThat(ctx.get("entrada")).isEmpty();
    }

    @Test
    @DisplayName("o passo do tipo sem execução, executado por fora do motor, falha com a mesma mensagem")
    void passoSemExecucaoFalhaComAMensagem() {
        Flow flow = flow("exec-loop", List.of(node("laco", "loop", "Laço", null)));

        assertThat(flow.getSteps().get(0)).isInstanceOf(UnsupportedNodeStep.class);
        StepResult result = flow.getSteps().get(0).execute(context("z")).join();
        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getErrors()).singleElement()
                .satisfies(e -> assertThat(e.message())
                        .isEqualTo("o tipo de nó 'loop' ainda não tem execução no servidor"));
    }

    @Test
    @DisplayName("um componente do catálogo com o mesmo id não isenta o nó: decide a lista, como no designer")
    void pluginComOMesmoIdContinuaMarcado() {
        // O designer marca o nó e bloqueia o Executar só pela lista (não conhece os plugins); se o catálogo
        // isentasse o nó aqui, o editor recusaria um fluxo que o servidor roda.
        ComponentCatalog catalog = mock(ComponentCatalog.class);
        when(catalog.getComponent(anyString())).thenReturn(Optional.empty());
        when(catalog.getComponent("custom")).thenReturn(Optional.of(mock(AIComponent.class)));

        var step = factory(catalog).create(Map.of("id", "c", "type", "custom", "componentId", "custom"));

        assertThat(step).isInstanceOf(UnsupportedNodeStep.class);
    }

    @Test
    @DisplayName("a galeria de templates marca o tipo sem execução e não marca Entrada/Saída")
    void nodeSupportSegueOsPassos() {
        NodeSupport support = new NodeSupport(factory(emptyCatalog()));

        assertThat(support.unsupportedNodeTypes(List.of(
                node("a", "input", "Entrada", null),
                node("b", "transform", "Transformar", null),
                node("c", "output", "Saída", null))))
                .containsExactly("transform");
    }
}
