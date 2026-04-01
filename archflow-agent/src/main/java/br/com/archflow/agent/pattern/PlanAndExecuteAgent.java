package br.com.archflow.agent.pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;

/**
 * Plan-and-Execute agent pattern.
 *
 * <p>Separates planning from execution for better cost efficiency:
 * <ol>
 *   <li><b>Planner</b>: Strong model generates a multi-step plan</li>
 *   <li><b>Executor</b>: Cheaper model executes each step</li>
 *   <li><b>Replanner</b>: Adjusts plan based on execution results</li>
 * </ol>
 *
 * <p>More token-efficient than ReAct for long tasks since planning
 * happens once (or few times) rather than at every step.
 */
public class PlanAndExecuteAgent {

    private static final Logger log = LoggerFactory.getLogger(PlanAndExecuteAgent.class);

    private final Function<String, List<PlanStep>> planner;
    private final Function<PlanStep, StepResult> executor;
    private final Function<ReplanContext, List<PlanStep>> replanner;
    private final int maxReplans;

    private PlanAndExecuteAgent(Builder builder) {
        this.planner = Objects.requireNonNull(builder.planner);
        this.executor = Objects.requireNonNull(builder.executor);
        this.replanner = builder.replanner;
        this.maxReplans = builder.maxReplans;
    }

    public static Builder builder() { return new Builder(); }

    public ExecutionResult execute(String task) {
        log.info("Planning for task: {}", task);
        List<PlanStep> plan = planner.apply(task);
        if (plan == null || plan.isEmpty()) {
            return new ExecutionResult(false, List.of(), 0, "Planner returned empty plan");
        }
        List<StepResult> results = new ArrayList<>();
        int replanCount = 0;

        for (int i = 0; i < plan.size(); i++) {
            PlanStep step = plan.get(i);
            log.debug("Executing step {}/{}: {}", i + 1, plan.size(), step.description());

            StepResult result = executor.apply(step);
            results.add(result);

            if (!result.success() && replanner != null && replanCount < maxReplans) {
                log.info("Step {} failed, replanning (attempt {}/{})", i + 1, replanCount + 1, maxReplans);
                ReplanContext ctx = new ReplanContext(task, plan, results, i);
                plan = replanner.apply(ctx);
                if (plan == null || plan.isEmpty()) {
                    return new ExecutionResult(false, results, replanCount, "Replanner returned empty plan");
                }
                i = -1; // restart from beginning of new plan
                results.clear();
                replanCount++;
            } else if (!result.success()) {
                log.warn("Step {} failed and no replanning available", i + 1);
                return new ExecutionResult(false, results, replanCount, "Step failed: " + step.description());
            }
        }

        return new ExecutionResult(true, results, replanCount, null);
    }

    // Inner types
    public record PlanStep(int order, String description, String type, Map<String, Object> parameters) {
        public PlanStep { if (parameters == null) parameters = Map.of(); }
    }

    public record StepResult(boolean success, String output, Map<String, Object> data) {
        public static StepResult ok(String output) { return new StepResult(true, output, Map.of()); }
        public static StepResult fail(String reason) { return new StepResult(false, reason, Map.of()); }
    }

    public record ReplanContext(String originalTask, List<PlanStep> failedPlan, List<StepResult> results, int failedAtIndex) {}

    public record ExecutionResult(boolean success, List<StepResult> stepResults, int replanCount, String error) {}

    public static class Builder {
        private Function<String, List<PlanStep>> planner;
        private Function<PlanStep, StepResult> executor;
        private Function<ReplanContext, List<PlanStep>> replanner;
        private int maxReplans = 2;

        public Builder planner(Function<String, List<PlanStep>> fn) { this.planner = fn; return this; }
        public Builder executor(Function<PlanStep, StepResult> fn) { this.executor = fn; return this; }
        public Builder replanner(Function<ReplanContext, List<PlanStep>> fn) { this.replanner = fn; return this; }
        public Builder maxReplans(int max) { this.maxReplans = max; return this; }
        public PlanAndExecuteAgent build() { return new PlanAndExecuteAgent(this); }
    }
}
