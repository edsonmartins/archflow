package br.com.archflow.api.workflow;

import br.com.archflow.model.flow.FlowMetadata;
import br.com.archflow.model.flow.StepType;
import br.com.archflow.standalone.FlowSerializer;
import br.com.archflow.standalone.model.SerializableConnection;
import br.com.archflow.standalone.model.SerializableFlow;
import br.com.archflow.standalone.model.SerializableFlowConfig;
import br.com.archflow.standalone.model.SerializableStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowYamlBridgeTest {

    private WorkflowYamlBridge bridge;

    @BeforeEach
    void setUp() {
        bridge = new WorkflowYamlBridge();
    }

    private SerializableFlow sample() {
        var step1 = new SerializableStep("s1", StepType.AGENT, "agent", "execute",
                Map.of(), List.of(new SerializableConnection("s1", "s2", null, false)));
        var step2 = new SerializableStep("s2", StepType.TOOL, "tool", "run", Map.of(), List.of());
        var cfg = new SerializableFlowConfig(30_000, null, null, null);
        var meta = new FlowMetadata("Sample", "Desc", "1.0.0", "test", "author", List.of("tag"));
        return new SerializableFlow("wf-1", meta, List.of(step1, step2), cfg);
    }

    @Test
    @DisplayName("toYaml returns a DTO with id, YAML body and version")
    void toYaml() {
        WorkflowYamlDto dto = bridge.toYaml(sample(), "1.0.0");

        assertThat(dto.id()).isEqualTo("wf-1");
        assertThat(dto.version()).isEqualTo("1.0.0");
        assertThat(dto.yaml()).contains("wf-1").contains("Sample");
    }

    @Test
    @DisplayName("fromYaml parses a valid YAML into a SerializableFlow")
    void fromYaml() {
        String yaml = bridge.toYaml(sample(), null).yaml();
        SerializableFlow restored = bridge.fromYaml(yaml);

        assertThat(restored.getId()).isEqualTo("wf-1");
        assertThat(restored.getSteps()).hasSize(2);
    }

    @Test
    @DisplayName("fromYaml rejects empty payload")
    void rejectEmpty() {
        assertThatThrownBy(() -> bridge.fromYaml(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bridge.fromYaml(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("fromYaml rejects YAML without an id field")
    void rejectMissingId() {
        String yaml = """
                metadata:
                  name: NoId
                  description: no id
                  version: 1.0.0
                  category: test
                  author: me
                  tags: []
                steps: []
                """;
        assertThatThrownBy(() -> bridge.fromYaml(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
    }

    @Test
    @DisplayName("fromYaml wraps malformed YAML into IllegalArgumentException")
    void rejectMalformed() {
        assertThatThrownBy(() -> bridge.fromYaml(": this is : not : valid yaml ::"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("jsonToYaml round-trip returns YAML equivalent to direct serialize")
    void jsonToYaml() throws Exception {
        SerializableFlow flow = sample();
        String json = new FlowSerializer().serialize(flow);
        String yaml = bridge.jsonToYaml(json);
        String direct = bridge.toYaml(flow, null).yaml();

        assertThat(yaml).isEqualTo(direct);
    }

    @Test
    @DisplayName("round-trip preserves the designer fields (label, operation, position, configuration)")
    void preservesDesignerFields() throws Exception {
        // The visual designer persists label/operation/position/configuration.
        // A YAML round-trip must keep them, or opening the Code tab and saving
        // would silently reset node names, drop the execution operation, and
        // pile every node at the default position.
        String json = """
                {
                  "id": "wf-1",
                  "steps": [
                    {
                      "id": "s1",
                      "type": "TOOL",
                      "componentId": "summarizer",
                      "label": "Summarize Ticket",
                      "operation": "summarize",
                      "position": { "x": 120, "y": 80 },
                      "configuration": { "temperature": 0.2 },
                      "connections": []
                    }
                  ]
                }
                """;

        String yaml = bridge.jsonToYaml(json);
        assertThat(yaml).contains("Summarize Ticket").contains("summarize").contains("configuration");

        SerializableFlow restored = new FlowSerializer().deserialize(bridge.yamlToJson(yaml));
        var step = (SerializableStep) restored.getSteps().get(0);
        assertThat(step.getLabel()).isEqualTo("Summarize Ticket");
        assertThat(step.getOperation()).isEqualTo("summarize");
        assertThat(step.getConfiguration()).containsEntry("temperature", 0.2);
        assertThat(step.getPosition()).containsKeys("x", "y");
    }

    @Test
    @DisplayName("yamlToJson produces a parseable JSON")
    void yamlToJson() throws Exception {
        SerializableFlow flow = sample();
        String yaml = bridge.toYaml(flow, null).yaml();
        String json = bridge.yamlToJson(yaml);

        SerializableFlow restored = new FlowSerializer().deserialize(json);
        assertThat(restored.getId()).isEqualTo(flow.getId());
        assertThat(restored.getSteps()).hasSize(2);
    }
}
