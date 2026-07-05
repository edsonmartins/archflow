package br.com.archflow.standalone.model;

import br.com.archflow.model.config.LLMConfigPatch;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.model.flow.StepType;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Serializable implementation of FlowStep.
 *
 * <p>This DTO is the shared JSON↔YAML round-trip model (see
 * {@code WorkflowYamlBridge}), so it must carry every field the visual
 * designer persists — otherwise editing a flow through the Code/YAML tab
 * silently strips them. The designer's schema is
 * {@code id/type/componentId/label/operation/position/configuration/connections}.
 * Legacy/standalone flows used the {@code config} key; {@link JsonAlias}
 * normalizes it onto the single {@code configuration} field so there are never
 * two config maps that can disagree. Null fields are omitted so
 * designer-authored flows don't accumulate {@code operation: null} noise.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SerializableStep implements FlowStep {

    private String id;
    private StepType type;
    private String componentId;
    /** Display name shown on the canvas node (designer field). */
    private String label;
    private String operation;
    /** Canvas coordinates ({x,y}); preserved so a YAML round-trip keeps the layout. */
    private Map<String, Object> position;
    /** Node configuration. Accepts the legacy {@code config} key on input. */
    @JsonAlias("config")
    private Map<String, Object> configuration;
    private List<SerializableConnection> connections;

    public SerializableStep() {} // Jackson

    public SerializableStep(String id, StepType type, String componentId, String operation,
                             Map<String, Object> config, List<SerializableConnection> connections) {
        this.id = id;
        this.type = type;
        this.componentId = componentId;
        this.operation = operation;
        this.configuration = config != null ? Map.copyOf(config) : Map.of();
        this.connections = connections != null ? List.copyOf(connections) : List.of();
    }

    public static SerializableStep from(FlowStep step) {
        List<SerializableConnection> conns = step.getConnections().stream()
                .map(SerializableConnection::from)
                .toList();
        return new SerializableStep(step.getId(), step.getType(), null, null, Map.of(), conns);
    }

    @Override public String getId() { return id; }
    @Override public StepType getType() { return type; }
    @Override @SuppressWarnings("unchecked")
    public List<StepConnection> getConnections() { return (List<StepConnection>) (List<?>) connections; }

    @Override
    public CompletableFuture<StepResult> execute(ExecutionContext context) {
        // Resolved at runtime by StandaloneRunner via componentId + operation
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Use StandaloneRunner to execute steps"));
    }

    /**
     * Deriva o override de LLM deste passo a partir do {@code config} salvo pela
     * UI (chaves: provider, model, temperature, maxTokens, timeout,
     * additionalConfig). Campos ausentes ficam vazios e são herdados na cadeia
     * de resolução.
     */
    @Override
    public LLMConfigPatch getLLMPatch() {
        return LLMConfigPatch.fromMap(configuration != null ? configuration : Map.of());
    }

    public String getComponentId() { return componentId; }
    public String getLabel() { return label; }
    public String getOperation() { return operation; }
    public Map<String, Object> getPosition() { return position; }
    public Map<String, Object> getConfiguration() { return configuration; }

    public void setId(String id) { this.id = id; }
    public void setType(StepType type) { this.type = type; }
    public void setComponentId(String componentId) { this.componentId = componentId; }
    public void setLabel(String label) { this.label = label; }
    public void setOperation(String operation) { this.operation = operation; }
    public void setPosition(Map<String, Object> position) { this.position = position; }
    public void setConfiguration(Map<String, Object> configuration) { this.configuration = configuration; }
    public void setConnections(List<SerializableConnection> connections) { this.connections = connections; }
}
