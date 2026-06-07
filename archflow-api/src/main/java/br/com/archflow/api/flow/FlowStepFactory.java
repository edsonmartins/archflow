package br.com.archflow.api.flow;

import br.com.archflow.model.flow.FlowStep;

import java.util.Map;

/** Builds an executable {@link FlowStep} from a workflow-JSON node (design-0004). */
public interface FlowStepFactory {
    FlowStep create(Map<String, Object> node);
}
