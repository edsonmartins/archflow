package br.com.archflow.api.flow;

import br.com.archflow.model.config.FlowConfiguration;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowMetadata;
import br.com.archflow.model.flow.FlowStep;

import java.util.List;

/**
 * Minimal {@link Flow} produced by the {@link WorkflowDeserializer} for the
 * linear runner (design-0004 step 1). {@code getConfiguration()} is null — the
 * linear runner does not consume it; the full engine path (with config/retry/
 * branches) is a follow-up.
 */
public final class SimpleFlow implements Flow {

    private final String id;
    private final FlowMetadata metadata;
    private final List<FlowStep> steps;

    public SimpleFlow(String id, FlowMetadata metadata, List<FlowStep> steps) {
        this.id = id;
        this.metadata = metadata;
        this.steps = List.copyOf(steps);
    }

    @Override public String getId() { return id; }
    @Override public FlowMetadata getMetadata() { return metadata; }
    @Override public List<FlowStep> getSteps() { return steps; }
    @Override public FlowConfiguration getConfiguration() { return null; }

    @Override
    public void validate() {
        if (metadata == null) {
            throw new IllegalStateException("flow metadata is required");
        }
        // Structural validity only; step-level validation is the step's concern.
    }
}

