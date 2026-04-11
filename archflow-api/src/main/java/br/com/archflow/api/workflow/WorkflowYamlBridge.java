package br.com.archflow.api.workflow;

import br.com.archflow.model.flow.Flow;
import br.com.archflow.standalone.FlowSerializer;
import br.com.archflow.standalone.YamlFlowSerializer;
import br.com.archflow.standalone.model.SerializableFlow;

import java.io.IOException;
import java.util.Objects;

/**
 * Stateless helper that turns a {@link Flow} into YAML and back, reusing
 * the same {@link SerializableFlow} graph across JSON and YAML endpoints.
 *
 * <p>Framework bindings instantiate a single bridge per process and
 * delegate to it from the controller layer. Both serializers are
 * allocated once, kept thread-safe by Jackson and reused across calls.
 *
 * <p>The bridge deliberately throws {@link IllegalArgumentException} on
 * malformed payloads so Spring's default 400 handler maps the error
 * without additional configuration.
 */
public class WorkflowYamlBridge {

    private final YamlFlowSerializer yamlSerializer;
    private final FlowSerializer jsonSerializer;

    public WorkflowYamlBridge() {
        this(new YamlFlowSerializer(), new FlowSerializer());
    }

    public WorkflowYamlBridge(YamlFlowSerializer yamlSerializer, FlowSerializer jsonSerializer) {
        this.yamlSerializer = Objects.requireNonNull(yamlSerializer);
        this.jsonSerializer = Objects.requireNonNull(jsonSerializer);
    }

    /**
     * Serialize a {@link Flow} to YAML. The returned {@link WorkflowYamlDto}
     * keeps the workflow id and version so the UI can detect stale edits.
     */
    public WorkflowYamlDto toYaml(Flow flow, String version) {
        Objects.requireNonNull(flow, "flow");
        try {
            String yaml = yamlSerializer.serialize(flow);
            return new WorkflowYamlDto(flow.getId(), yaml, version);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize workflow " + flow.getId(), e);
        }
    }

    /**
     * Parse a YAML payload and return a ready-to-persist
     * {@link SerializableFlow}. Throws {@link IllegalArgumentException}
     * when the YAML is malformed or missing required fields.
     */
    public SerializableFlow fromYaml(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            throw new IllegalArgumentException("YAML payload is empty");
        }
        try {
            SerializableFlow flow = yamlSerializer.deserialize(yaml);
            if (flow.getId() == null || flow.getId().isBlank()) {
                throw new IllegalArgumentException("YAML workflow missing 'id'");
            }
            return flow;
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed YAML: " + e.getMessage(), e);
        }
    }

    /**
     * Convenience bridge: converts a JSON string to YAML. Useful for
     * binding layers that still persist workflows as JSON internally.
     */
    public String jsonToYaml(String json) {
        Objects.requireNonNull(json, "json");
        try {
            SerializableFlow flow = jsonSerializer.deserialize(json);
            return yamlSerializer.serialize(flow);
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Convenience bridge: converts a YAML string to JSON.
     */
    public String yamlToJson(String yaml) {
        Objects.requireNonNull(yaml, "yaml");
        try {
            SerializableFlow flow = yamlSerializer.deserialize(yaml);
            return jsonSerializer.serialize(flow);
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed YAML: " + e.getMessage(), e);
        }
    }
}
