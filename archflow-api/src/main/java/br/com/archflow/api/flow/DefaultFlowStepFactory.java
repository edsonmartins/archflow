package br.com.archflow.api.flow;

import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepType;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Maps a workflow-JSON node to an executable {@link FlowStep}: an
 * {@link OrchestrateStep} for {@code type="ORCHESTRATE"} (design-0004 step 2),
 * otherwise a {@link ComponentStep} (ASSISTANT/AGENT/TOOL). The remaining
 * granular orchestration kinds (FAN_OUT/VERIFY/LOOP_UNTIL) are a follow-up.
 */
@Component
public class DefaultFlowStepFactory implements FlowStepFactory {

    private final ComponentCatalog catalog;
    private final DynamicWorkflowService dynamicWorkflowService;

    public DefaultFlowStepFactory(ComponentCatalog catalog, DynamicWorkflowService dynamicWorkflowService) {
        this.catalog = catalog;
        this.dynamicWorkflowService = dynamicWorkflowService;
    }

    @Override
    public FlowStep create(Map<String, Object> node) {
        String id = str(node.get("id"), "step");
        String type = str(node.get("type"), "");
        Map<String, Object> config = asMap(node.get("config"));

        if (StepType.ORCHESTRATE.name().equalsIgnoreCase(type)) {
            return new OrchestrateStep(id, List.of(), config, dynamicWorkflowService);
        }

        // componentId, falling back to the node "type" (the designer often uses
        // the component type as the node type, e.g. "llm-chat").
        Object componentId = node.get("componentId");
        if (componentId == null) {
            componentId = node.get("type");
        }
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
