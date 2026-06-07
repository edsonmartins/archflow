package br.com.archflow.api.flow;

import br.com.archflow.agent.orchestration.OrchestrationListener;
import br.com.archflow.agent.orchestration.SupervisorResult;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.api.orchestration.DynamicWorkflowRequest;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.engine.core.StateManager;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.model.flow.StepType;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Executable {@link FlowStep} for an {@code ORCHESTRATE} node (ADR-0002 D6 /
 * design-0004 step 2): runs a full dynamic workflow (decompose → fan-out →
 * verify → loop-until-dry) via {@link DynamicWorkflowService}, sharing the flow's
 * {@link ExecutionContext} so the worker agents run in the same context. The
 * goal comes from the node config ({@code goal}) or, failing that, the flow's
 * current {@code "input"} variable; confirmed findings become the step output.
 */
public final class OrchestrateStep implements FlowStep {

    private final String id;
    private final List<StepConnection> connections;
    private final Map<String, Object> config;
    private final DynamicWorkflowService service;
    private final EventStreamRegistry streamRegistry;
    private final StateManager stateManager;

    public OrchestrateStep(String id, List<StepConnection> connections,
                           Map<String, Object> config, DynamicWorkflowService service,
                           EventStreamRegistry streamRegistry, StateManager stateManager) {
        this.id = id;
        this.connections = connections == null ? List.of() : List.copyOf(connections);
        this.config = config == null ? Map.of() : config;
        this.service = service;
        this.streamRegistry = streamRegistry;
        this.stateManager = stateManager;
    }

    @Override public String getId() { return id; }
    @Override public StepType getType() { return StepType.ORCHESTRATE; }
    @Override public List<StepConnection> getConnections() { return connections; }

    @Override
    public CompletableFuture<StepResult> execute(ExecutionContext context) {
        long start = System.nanoTime();

        String goal = str(config.get("goal"));
        if (isBlank(goal)) {
            goal = context.get(ComponentStep.INPUT_KEY).map(Object::toString).orElse(null);
        }
        if (isBlank(goal)) {
            return CompletableFuture.completedFuture(SimpleStepResult.failed(
                    id, "ORCHESTRATE step requires a 'goal' (config) or an 'input' value", elapsedMs(start)));
        }

        DynamicWorkflowRequest req = new DynamicWorkflowRequest(
                goal,
                str(config.get("decomposePrompt")),
                intOrNull(config.get("maxSubtasks")),
                intOrNull(config.get("voters")),
                intOrNull(config.get("minAgree")),
                intOrNull(config.get("maxRounds")),
                intOrNull(config.get("concurrency")),
                longOrNull(config.get("budgetTokens")),
                context.getTenantId());

        try {
            // Stream the dynamic subtask tree live (SSE) and materialize it as
            // ExecutionPaths on the FlowState so it's persisted/inspectable.
            OrchestrationListener streaming = streamRegistry != null
                    ? new StreamingOrchestrationListener(streamRegistry, context.getSessionId())
                    : OrchestrationListener.NOOP;
            FlowState state = context.getState();
            OrchestrationListener listener = state != null
                    ? new MaterializingOrchestrationListener(state, streaming)
                    : streaming;

            SupervisorResult result = service.runOn(req, context, listener);
            List<Object> confirmed = result.confirmed();
            context.set(id, confirmed);
            context.set(ComponentStep.INPUT_KEY, confirmed);

            if (state != null && stateManager != null) {
                stateManager.saveState(context.getSessionId(), state);
            }
            return CompletableFuture.completedFuture(SimpleStepResult.ok(id, confirmed, elapsedMs(start)));
        } catch (RuntimeException e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            return CompletableFuture.completedFuture(SimpleStepResult.failed(id, msg, elapsedMs(start)));
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static Integer intOrNull(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Integer.valueOf(s.trim()); } catch (NumberFormatException ignored) { /* fall through */ }
        }
        return null;
    }

    private static Long longOrNull(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Long.valueOf(s.trim()); } catch (NumberFormatException ignored) { /* fall through */ }
        }
        return null;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
