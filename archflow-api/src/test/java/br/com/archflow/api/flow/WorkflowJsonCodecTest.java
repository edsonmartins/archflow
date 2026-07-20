package br.com.archflow.api.flow;

import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@DisplayName("WorkflowJsonCodec")
class WorkflowJsonCodecTest {

    private final WorkflowDeserializer deserializer = new DefaultWorkflowDeserializer(
            new DefaultFlowStepFactory(mock(ComponentCatalog.class), mock(DynamicWorkflowService.class),
                    mock(br.com.archflow.agent.streaming.EventStreamRegistry.class),
                    mock(br.com.archflow.engine.core.StateManager.class)));

    private final WorkflowJsonCodec codec = new WorkflowJsonCodec(deserializer);

    @Test
    @DisplayName("round-trip lossless: documento do designer → Flow → JSON → Flow")
    void roundTrip() throws Exception {
        Map<String, Object> document = Map.of(
                "id", "wf1",
                "metadata", Map.of("name", "Audit", "version", "1.2.0", "description", "d"),
                "steps", List.of(
                        Map.of("id", "s1", "componentId", "c1", "config", Map.of()),
                        Map.of("id", "s2", "type", "llm-chat")));

        Flow flow = deserializer.toFlow(document);
        String json = codec.toJson(flow);

        // O JSON persistido é o próprio documento do designer
        @SuppressWarnings("unchecked")
        Map<String, Object> persisted = new ObjectMapper().readValue(json, Map.class);
        assertThat(persisted).isEqualTo(document);

        Flow restored = codec.fromJson(json);
        assertThat(restored.getId()).isEqualTo("wf1");
        assertThat(restored.getSteps()).hasSize(2);
        assertThat(restored.getMetadata().name()).isEqualTo("Audit");
    }

    @Test
    @DisplayName("flow sem documento de origem é rejeitado com erro claro")
    void flowWithoutSourceDocumentIsRejected() {
        Flow programmatic = new SimpleFlow("p1",
                new br.com.archflow.model.flow.FlowMetadata("n", "d", "1", null, null, List.of()),
                List.of());

        assertThatThrownBy(() -> codec.toJson(programmatic))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source document");
    }
}
