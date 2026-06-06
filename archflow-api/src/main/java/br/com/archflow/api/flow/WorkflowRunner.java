package br.com.archflow.api.flow;

import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.StepStatus;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal sequential workflow runner (design-0004 step 1): executes a flow's
 * steps in order via {@link FlowStep#execute}, threading each step's output into
 * the next, and stopping on the first failure. This is the foundation that makes
 * stored workflows actually run; the full {@code FlowEngine} path (parallel
 * branches, retry, pause/resume) is a follow-up.
 */
@Service
public class WorkflowRunner {

    public record StepOutcome(String stepId, String status, Object output) {}

    public record RunResult(boolean success, List<StepOutcome> steps) {}

    public RunResult run(Flow flow, ExecutionContext context) {
        List<StepOutcome> outcomes = new ArrayList<>();
        boolean success = true;
        for (FlowStep step : flow.getSteps()) {
            StepResult result = step.execute(context).join();
            boolean ok = result.getStatus() == StepStatus.COMPLETED;
            success = success && ok;
            outcomes.add(new StepOutcome(result.getStepId(), result.getStatus().name(),
                    result.getOutput().orElse(null)));
            if (!ok) {
                break; // stop the chain on the first failed step
            }
        }
        return new RunResult(success, outcomes);
    }
}
