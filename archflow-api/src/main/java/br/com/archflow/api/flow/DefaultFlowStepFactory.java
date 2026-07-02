package br.com.archflow.api.flow;

import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.engine.core.StateManager;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.model.flow.StepType;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    private final EventStreamRegistry streamRegistry;
    private final StateManager stateManager;

    public DefaultFlowStepFactory(ComponentCatalog catalog, DynamicWorkflowService dynamicWorkflowService,
                                  EventStreamRegistry streamRegistry, StateManager stateManager) {
        this.catalog = catalog;
        this.dynamicWorkflowService = dynamicWorkflowService;
        this.streamRegistry = streamRegistry;
        this.stateManager = stateManager;
    }

    @Override
    public FlowStep create(Map<String, Object> node) {
        String id = str(node.get("id"), "step");
        String type = str(node.get("type"), "");
        Map<String, Object> config = config(node);
        List<StepConnection> connections = connections(id, node.get("connections"));

        if (StepType.ORCHESTRATE.name().equalsIgnoreCase(type)) {
            return new OrchestrateStep(id, connections, config, dynamicWorkflowService, streamRegistry, stateManager);
        }

        // componentId, falling back to the node "type" (the designer often uses
        // the component type as the node type, e.g. "llm-chat").
        Object componentId = node.get("componentId");
        if (componentId == null) {
            componentId = node.get("type");
        }
        // Explicit config wins; the node-level field is a fallback for
        // YAML/API-authored flows. Designer saves keep the display name in
        // `label`, so it never reaches the execution operation.
        String operation = str(config.get("operation"), str(node.get("operation"), "execute"));
        return new ComponentStep(
                id, StepType.TOOL,
                componentId == null ? "" : componentId.toString(),
                operation, connections, catalog);
    }

    private static String str(Object v, String fallback) {
        return v == null ? fallback : v.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static Map<String, Object> config(Map<String, Object> node) {
        Map<String, Object> config = new LinkedHashMap<>(asMap(node.get("config")));
        config.putAll(asMap(node.get("configuration")));
        return config.isEmpty() ? Map.of() : Collections.unmodifiableMap(config);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object v) {
        return v instanceof List<?> l ? (List<Object>) l : List.of();
    }

    private static List<StepConnection> connections(String stepId, Object value) {
        List<StepConnection> connections = new ArrayList<>();
        for (Object item : asList(value)) {
            Map<String, Object> map = asMap(item);
            String sourceId = str(map.get("sourceId"), stepId);
            String targetId = str(map.get("targetId"), "");
            if (targetId.isBlank()) {
                continue;
            }
            String condition = str(map.get("condition"), null);
            boolean errorPath = bool(map.get("isErrorPath"),
                    bool(map.get("errorPath"), "error".equals(map.get("type"))));
            connections.add(new PersistedStepConnection(sourceId, targetId, condition, errorPath));
        }
        return List.copyOf(connections);
    }

    private static boolean bool(Object v, boolean fallback) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s && !s.isBlank()) {
            return Boolean.parseBoolean(s);
        }
        return fallback;
    }

    private static final class PersistedStepConnection implements StepConnection {
        private final String sourceId;
        private final String targetId;
        private final String condition;
        private final boolean errorPath;

        private PersistedStepConnection(String sourceId, String targetId, String condition, boolean errorPath) {
            this.sourceId = sourceId;
            this.targetId = targetId;
            this.condition = condition;
            this.errorPath = errorPath;
        }

        @Override
        public String getSourceId() {
            return sourceId;
        }

        @Override
        public String getTargetId() {
            return targetId;
        }

        @Override
        public Optional<String> getCondition() {
            return Optional.ofNullable(condition);
        }

        @Override
        public boolean isErrorPath() {
            return errorPath;
        }
    }
}
