package br.com.archflow.api.flow;

import br.com.archflow.model.config.FlowConfiguration;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowMetadata;
import br.com.archflow.model.flow.FlowStep;

import java.util.List;

/**
 * Minimal {@link Flow} produced by the {@link WorkflowDeserializer} for the
 * linear runner (design-0004 step 1). A configuração preserva o patch de LLM
 * do fluxo para que qualquer executor use a mesma cadeia de resolução.
 */
public final class SimpleFlow implements Flow {

    private final String id;
    private final FlowMetadata metadata;
    private final List<FlowStep> steps;
    private final FlowConfiguration configuration;
    /**
     * Documento JSON do designer que originou este flow (null quando o flow
     * foi construído programaticamente). Permite ao {@link WorkflowJsonCodec}
     * persistir a definição sem perda — os FlowSteps materializados não são
     * serializáveis de volta.
     */
    private final java.util.Map<String, Object> sourceDocument;

    public SimpleFlow(String id, FlowMetadata metadata, List<FlowStep> steps) {
        this(id, metadata, steps, null, null);
    }

    public SimpleFlow(String id, FlowMetadata metadata, List<FlowStep> steps,
                      java.util.Map<String, Object> sourceDocument) {
        this(id, metadata, steps, sourceDocument, null);
    }

    public SimpleFlow(String id, FlowMetadata metadata, List<FlowStep> steps,
                      java.util.Map<String, Object> sourceDocument,
                      FlowConfiguration configuration) {
        this.id = id;
        this.metadata = metadata;
        this.steps = List.copyOf(steps);
        this.sourceDocument = sourceDocument;
        this.configuration = configuration;
    }

    @Override public String getId() { return id; }
    @Override public FlowMetadata getMetadata() { return metadata; }
    @Override public List<FlowStep> getSteps() { return steps; }
    @Override public FlowConfiguration getConfiguration() { return configuration; }

    public java.util.Map<String, Object> getSourceDocument() { return sourceDocument; }

    @Override
    public void validate() {
        if (metadata == null) {
            throw new IllegalStateException("flow metadata is required");
        }
        // Structural validity only; step-level validation is the step's concern.
    }
}
