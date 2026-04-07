package br.com.archflow.standalone.model;

import br.com.archflow.model.config.FlowConfiguration;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowMetadata;
import br.com.archflow.model.flow.FlowStep;

import java.util.List;
import java.util.Objects;

/**
 * Serializable implementation of Flow for standalone export.
 *
 * <p>Wraps all flow data in a concrete class that Jackson can serialize/deserialize.
 * The execute() logic on steps is resolved at runtime by the StandaloneRunner.
 */
public class SerializableFlow implements Flow {

    private String id;
    private FlowMetadata metadata;
    private List<SerializableStep> steps;
    private SerializableFlowConfig configuration;

    public SerializableFlow() {} // Jackson

    public SerializableFlow(String id, FlowMetadata metadata, List<SerializableStep> steps,
                             SerializableFlowConfig configuration) {
        this.id = Objects.requireNonNull(id);
        this.metadata = metadata;
        this.steps = steps != null ? List.copyOf(steps) : List.of();
        this.configuration = configuration;
    }

    /**
     * Creates a SerializableFlow from any Flow interface implementation.
     */
    public static SerializableFlow from(Flow flow) {
        List<SerializableStep> steps = flow.getSteps().stream()
                .map(SerializableStep::from)
                .toList();
        SerializableFlowConfig config = flow.getConfiguration() != null
                ? SerializableFlowConfig.from(flow.getConfiguration())
                : null;
        return new SerializableFlow(flow.getId(), flow.getMetadata(), steps, config);
    }

    @Override public String getId() { return id; }
    @Override public FlowMetadata getMetadata() { return metadata; }
    @Override @SuppressWarnings("unchecked") public List<FlowStep> getSteps() { return (List<FlowStep>) (List<?>) steps; }
    @Override public FlowConfiguration getConfiguration() { return configuration; }
    @Override public void validate() { /* Validated during deserialization */ }

    public void setId(String id) { this.id = id; }
    public void setMetadata(FlowMetadata metadata) { this.metadata = metadata; }
    public void setSteps(List<SerializableStep> steps) { this.steps = steps; }
    public void setConfiguration(SerializableFlowConfig configuration) { this.configuration = configuration; }
}
