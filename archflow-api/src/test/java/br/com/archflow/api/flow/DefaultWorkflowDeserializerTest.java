package br.com.archflow.api.flow;

import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DefaultWorkflowDeserializerTest {

    private final WorkflowDeserializer deserializer = new DefaultWorkflowDeserializer(
            new DefaultFlowStepFactory(mock(ComponentCatalog.class), mock(DynamicWorkflowService.class),
                    mock(br.com.archflow.agent.streaming.EventStreamRegistry.class),
                    mock(br.com.archflow.engine.core.StateManager.class)));

    @Test
    void buildsAnExecutableFlowFromWorkflowJson() {
        Map<String, Object> json = Map.of(
                "id", "wf1",
                "metadata", Map.of("name", "Audit", "version", "1.2.0", "description", "d"),
                "steps", List.of(
                        Map.of("id", "s1", "componentId", "c1", "config", Map.of()),
                        Map.of("id", "s2", "type", "llm-chat")));

        Flow flow = deserializer.toFlow(json);

        assertThat(flow.getId()).isEqualTo("wf1");
        assertThat(flow.getMetadata().name()).isEqualTo("Audit");
        assertThat(flow.getMetadata().version()).isEqualTo("1.2.0");
        assertThat(flow.getSteps()).hasSize(2);
        assertThat(flow.getSteps().get(0).getId()).isEqualTo("s1");
        assertThat(flow.getSteps().get(1).getId()).isEqualTo("s2");
        assertThat(flow.getSteps().get(0)).isInstanceOf(ComponentStep.class);
    }

    @Test
    void toleratesMissingMetadataAndSteps() {
        Flow flow = deserializer.toFlow(Map.of());

        assertThat(flow.getMetadata().name()).isEqualTo("Untitled");
        assertThat(flow.getMetadata().version()).isEqualTo("1.0.0");
        assertThat(flow.getSteps()).isEmpty();
    }
}
