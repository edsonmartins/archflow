package br.com.archflow.standalone.model;

import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.model.flow.StepType;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Serializable implementation of FlowStep.
 */
public class SerializableStep implements FlowStep {

    private String id;
    private StepType type;
    private String componentId;
    private String operation;
    private Map<String, Object> config;
    private List<SerializableConnection> connections;

    public SerializableStep() {} // Jackson

    public SerializableStep(String id, StepType type, String componentId, String operation,
                             Map<String, Object> config, List<SerializableConnection> connections) {
        this.id = id;
        this.type = type;
        this.componentId = componentId;
        this.operation = operation;
        this.config = config != null ? Map.copyOf(config) : Map.of();
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

    public String getComponentId() { return componentId; }
    public String getOperation() { return operation; }
    public Map<String, Object> getConfig() { return config; }

    public void setId(String id) { this.id = id; }
    public void setType(StepType type) { this.type = type; }
    public void setComponentId(String componentId) { this.componentId = componentId; }
    public void setOperation(String operation) { this.operation = operation; }
    public void setConfig(Map<String, Object> config) { this.config = config; }
    public void setConnections(List<SerializableConnection> connections) { this.connections = connections; }
}
