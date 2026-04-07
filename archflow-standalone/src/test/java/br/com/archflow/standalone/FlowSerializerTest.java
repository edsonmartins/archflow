package br.com.archflow.standalone;

import br.com.archflow.model.config.LLMConfig;
import br.com.archflow.model.config.RetryPolicy;
import br.com.archflow.model.flow.FlowMetadata;
import br.com.archflow.model.flow.StepType;
import br.com.archflow.standalone.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

@DisplayName("FlowSerializer")
class FlowSerializerTest {

    private FlowSerializer serializer;

    @BeforeEach
    void setUp() { serializer = new FlowSerializer(); }

    private SerializableFlow createTestFlow() {
        var conn = new SerializableConnection("step-1", "step-2", null, false);
        var step1 = new SerializableStep("step-1", StepType.AGENT, "research-agent", "execute",
                Map.of("maxRetries", 3), List.of(conn));
        var step2 = new SerializableStep("step-2", StepType.TOOL, "text-transform", "summarize",
                Map.of(), List.of());

        var llm = new LLMConfig("gpt-4o", 0.7, 2048, 30000, Map.of());
        var retry = new RetryPolicy(3, 1000, 2.0, Set.of());
        var config = new SerializableFlowConfig(60000, retry, llm, null);

        var metadata = new FlowMetadata("Test Workflow", "A test flow", "1.0.0",
                "test", "testing", List.of("test", "demo"));

        return new SerializableFlow("flow-1", metadata, List.of(step1, step2), config);
    }

    @Test @DisplayName("should serialize flow to JSON")
    void shouldSerialize() throws Exception {
        String json = serializer.serialize(createTestFlow());
        assertThat(json).contains("flow-1").contains("Test Workflow").contains("research-agent");
    }

    @Test @DisplayName("should deserialize flow from JSON")
    void shouldDeserialize() throws Exception {
        String json = serializer.serialize(createTestFlow());
        SerializableFlow flow = serializer.deserialize(json);
        assertThat(flow.getId()).isEqualTo("flow-1");
        assertThat(flow.getMetadata().name()).isEqualTo("Test Workflow");
    }

    @Test @DisplayName("should round-trip serialize/deserialize")
    void shouldRoundTrip() throws Exception {
        SerializableFlow original = createTestFlow();
        String json = serializer.serialize(original);
        SerializableFlow restored = serializer.deserialize(json);

        assertThat(restored.getId()).isEqualTo(original.getId());
        assertThat(restored.getSteps()).hasSize(2);
        assertThat(restored.getConfiguration().getTimeout()).isEqualTo(60000);
        assertThat(restored.getConfiguration().getLLMConfig().model()).isEqualTo("gpt-4o");
    }

    @Test @DisplayName("should preserve step connections")
    void shouldPreserveConnections() throws Exception {
        String json = serializer.serialize(createTestFlow());
        SerializableFlow flow = serializer.deserialize(json);
        var step = (SerializableStep) flow.getSteps().get(0);
        assertThat(step.getConnections()).hasSize(1);
        assertThat(step.getConnections().get(0).getTargetId()).isEqualTo("step-2");
    }

    @Test @DisplayName("should preserve step config")
    void shouldPreserveStepConfig() throws Exception {
        String json = serializer.serialize(createTestFlow());
        SerializableFlow flow = serializer.deserialize(json);
        var step = (SerializableStep) flow.getSteps().get(0);
        assertThat(step.getComponentId()).isEqualTo("research-agent");
        assertThat(step.getOperation()).isEqualTo("execute");
        assertThat(step.getConfig()).containsEntry("maxRetries", 3);
    }

    @Test @DisplayName("should export and import from file")
    void shouldExportImportFile(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("workflow.json");
        serializer.exportToFile(createTestFlow(), file);

        SerializableFlow imported = serializer.importFromFile(file);
        assertThat(imported.getId()).isEqualTo("flow-1");
    }

    @Test @DisplayName("should handle null configuration")
    void shouldHandleNullConfig() throws Exception {
        var flow = new SerializableFlow("f1", null, List.of(), null);
        String json = serializer.serialize(flow);
        SerializableFlow restored = serializer.deserialize(json);
        assertThat(restored.getId()).isEqualTo("f1");
        assertThat(restored.getConfiguration()).isNull();
    }
}
