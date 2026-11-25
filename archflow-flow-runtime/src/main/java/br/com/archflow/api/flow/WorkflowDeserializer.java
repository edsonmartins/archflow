package br.com.archflow.api.flow;

import br.com.archflow.model.flow.Flow;

import java.util.Map;

/** Parses a stored workflow JSON document into an executable {@link Flow} (design-0004). */
public interface WorkflowDeserializer {
    Flow toFlow(Map<String, Object> workflowJson);
}
