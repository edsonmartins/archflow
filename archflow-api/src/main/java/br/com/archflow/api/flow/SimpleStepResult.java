package br.com.archflow.api.flow;

import br.com.archflow.model.enums.StepStatus;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.model.metrics.StepMetrics;
// StepError intentionally not constructed here: the failure message is carried
// in getOutput(); getErrors() stays empty for the minimal linear runner.
import br.com.archflow.model.flow.StepError;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Minimal {@link StepResult} for the linear workflow runner (design-0004 step 1). */
public final class SimpleStepResult implements StepResult {

    private final String stepId;
    private final StepStatus status;
    private final Object output;
    private final long elapsedMs;
    private final List<StepError> errors;

    private SimpleStepResult(String stepId, StepStatus status, Object output, long elapsedMs, List<StepError> errors) {
        this.stepId = stepId;
        this.status = status;
        this.output = output;
        this.elapsedMs = elapsedMs;
        this.errors = errors;
    }

    public static SimpleStepResult ok(String stepId, Object output, long elapsedMs) {
        return new SimpleStepResult(stepId, StepStatus.COMPLETED, output, elapsedMs, List.of());
    }

    public static SimpleStepResult failed(String stepId, String message, long elapsedMs) {
        return new SimpleStepResult(stepId, StepStatus.FAILED, message, elapsedMs, List.of());
    }

    @Override public String getStepId() { return stepId; }
    @Override public StepStatus getStatus() { return status; }
    @Override public Optional<Object> getOutput() { return Optional.ofNullable(output); }
    @Override public StepMetrics getMetrics() { return new StepMetrics(elapsedMs, 0, 0, Map.of()); }
    @Override public List<StepError> getErrors() { return errors; }
}
