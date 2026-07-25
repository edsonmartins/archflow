package br.com.archflow.api.flow;

import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.StepStatus;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import br.com.archflow.plugin.api.catalog.DefaultComponentCatalog;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * O invariante que o catálogo global impedia: um fluxo que declara sua lista de
 * componentes não alcança nada fora dela — nem por erro de configuração, já que
 * o {@code componentId} do nó vem do JSON e era resolvido sem qualquer filtro.
 */
@DisplayName("Escopo de componentes por fluxo")
class FlowComponentScopeTest {

    private static final String READER = "log-reader";
    private static final String WRITER = "service-restarter";

    private final AtomicInteger writerRuns = new AtomicInteger();
    private ComponentCatalog catalog;
    private WorkflowDeserializer deserializer;

    private AIComponent component(String id, AtomicInteger counter) {
        return new AIComponent() {
            @Override public void initialize(Map<String, Object> config) { }
            @Override public ComponentMetadata getMetadata() {
                return new ComponentMetadata(id, id, "componente " + id, ComponentType.TOOL,
                        "1.0.0", Set.of(), List.of(), Map.of(), Set.of());
            }
            @Override public Object execute(String op, Object in, ExecutionContext ctx) {
                if (counter != null) {
                    counter.incrementAndGet();
                }
                return "saída de " + id;
            }
            @Override public void shutdown() { }
        };
    }

    @BeforeEach
    void setUp() {
        catalog = new DefaultComponentCatalog();
        catalog.register(component(READER, null));
        catalog.register(component(WRITER, writerRuns));
        deserializer = new DefaultWorkflowDeserializer(new DefaultFlowStepFactory(
                catalog, mock(DynamicWorkflowService.class), null, null, null));
    }

    private static ExecutionContext context() {
        return new DefaultExecutionContext("acme", "u", "run-1",
                MessageWindowChatMemory.builder().maxMessages(5).build());
    }

    /** Workflow JSON com um nó apontando para {@code componentId}. */
    private Map<String, Object> workflow(String componentId, List<String> allowed) {
        Map<String, Object> json = new java.util.HashMap<>();
        json.put("id", "wf-leitura");
        json.put("metadata", Map.of("name", "Leitura"));
        json.put("steps", List.of(Map.of("id", "s1", "type", "TOOL", "componentId", componentId)));
        if (allowed != null) {
            json.put("configuration", Map.of("allowedComponents", allowed));
        }
        return json;
    }

    private StepResult runFirstStep(Map<String, Object> json) {
        Flow flow = deserializer.toFlow(json);
        FlowStep step = flow.getSteps().get(0);
        return step.execute(context()).join();
    }

    @Test
    @DisplayName("componente fora da allowlist não executa, mesmo nomeado no JSON")
    void componentOutsideTheAllowlistDoesNotRun() {
        StepResult result = runFirstStep(workflow(WRITER, List.of(READER)));

        assertThat(writerRuns)
                .as("o fluxo de leitura nao pode tocar a tool de escrita nem por erro de config")
                .hasValue(0);
        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).asString().contains("component not found");
    }

    @Test
    @DisplayName("componente dentro da allowlist executa normalmente")
    void componentInsideTheAllowlistRuns() {
        StepResult result = runFirstStep(workflow(READER, List.of(READER)));

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).contains("saída de " + READER);
    }

    @Test
    @DisplayName("sem allowlist declarada, nada muda — workflows existentes seguem funcionando")
    void absentAllowlistKeepsHistoricBehaviour() {
        StepResult result = runFirstStep(workflow(WRITER, null));

        assertThat(writerRuns).hasValue(1);
        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
    }
}
