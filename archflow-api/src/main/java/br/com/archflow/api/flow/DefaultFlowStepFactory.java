package br.com.archflow.api.flow;

import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepType;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Maps a workflow-JSON node to an executable {@link FlowStep}. For now every node
 * becomes a {@link ComponentStep} (ASSISTANT/AGENT/TOOL); the orchestration node
 * kinds (ORCHESTRATE/FAN_OUT/VERIFY/LOOP_UNTIL) are design-0004 step 2.
 */
@Component
public class DefaultFlowStepFactory implements FlowStepFactory {

    private final ComponentCatalog catalog;

    public DefaultFlowStepFactory(ComponentCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public FlowStep create(Map<String, Object> node) {
        String id = str(node.get("id"), "step");
        // componentId, falling back to the node "type" (the designer often uses
        // the component type as the node type, e.g. "llm-chat").
        Object componentId = node.get("componentId");
        if (componentId == null) {
            componentId = node.get("type");
        }
        Map<String, Object> config = asMap(node.get("config"));
        String operation = str(config.get("operation"), "execute");
        return new ComponentStep(
                id, StepType.TOOL,
                componentId == null ? "" : componentId.toString(),
                operation, List.of(), catalog);
    }

    private static String str(Object v, String fallback) {
        return v == null ? fallback : v.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
