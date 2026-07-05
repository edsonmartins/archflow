package br.com.archflow.api.flow;

import br.com.archflow.api.web.workflow.InMemoryWorkflowRuntimeStore;
import br.com.archflow.engine.lifecycle.FlowLifecycleListener;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.model.flow.FlowStep;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Persists per-step lifecycle data (status, timings, errors) into the
 * execution record of the {@link InMemoryWorkflowRuntimeStore}, so
 * {@code GET /api/executions/{id}} can serve a step-by-step drill-down
 * instead of only the flow-level status. The flow id equals the
 * execution id in both execution paths ({@code /execute} and AG-UI).
 *
 * <p>Also records the flow's wall-clock {@code duration}, which the
 * store's {@code completeExecution} does not set.
 */
public final class StepRecordingListener implements FlowLifecycleListener {

    private final InMemoryWorkflowRuntimeStore store;

    public StepRecordingListener(InMemoryWorkflowRuntimeStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public void onStepStarted(Flow flow, FlowStep step, ExecutionContext context,
                              int stepIndex, int stepCount) {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("type", step.getType() != null ? step.getType().name() : null);
        patch.put("stepIndex", stepIndex);
        patch.put("stepCount", stepCount);
        patch.put("status", "RUNNING");
        patch.put("startedAt", Instant.now().toString());
        store.recordStep(flow.getId(), step.getId(), patch);
    }

    @Override
    public void onStepCompleted(Flow flow, FlowStep step, ExecutionContext context, long durationMs) {
        store.recordStep(flow.getId(), step.getId(), finished("COMPLETED", durationMs, null));
    }

    @Override
    public void onStepFailed(Flow flow, FlowStep step, ExecutionContext context,
                             Throwable error, long durationMs) {
        store.recordStep(flow.getId(), step.getId(),
                finished("FAILED", durationMs, error != null ? error.getMessage() : null));
    }

    @Override
    public void onStepSkipped(Flow flow, FlowStep step, ExecutionContext context) {
        store.recordStep(flow.getId(), step.getId(), finished("SKIPPED", null, null));
    }

    @Override
    public void onFlowCompleted(Flow flow, ExecutionContext context, FlowResult result, long durationMs) {
        store.recordDuration(flow.getId(), durationMs);
    }

    @Override
    public void onFlowFailed(Flow flow, ExecutionContext context, Throwable error, long durationMs) {
        store.recordDuration(flow.getId(), durationMs);
    }

    private static Map<String, Object> finished(String status, Long durationMs, String error) {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("status", status);
        patch.put("finishedAt", Instant.now().toString());
        if (durationMs != null) {
            patch.put("durationMs", durationMs);
        }
        if (error != null) {
            patch.put("error", error);
        }
        return patch;
    }
}
