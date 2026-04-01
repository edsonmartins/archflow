package br.com.archflow.agent.pattern;

import br.com.archflow.model.ai.domain.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/**
 * ReAct (Reason + Act) agent executor.
 *
 * <p>Implements the Thought -> Action -> Observation loop where the agent
 * interleaves reasoning with tool use. This is the standard agentic pattern
 * used by LangChain, LangGraph, CrewAI, and other frameworks.
 *
 * <p>The loop continues until:
 * <ul>
 *   <li>The agent decides to finish (returns a final answer)</li>
 *   <li>Max iterations reached</li>
 *   <li>Timeout exceeded</li>
 * </ul>
 *
 * <h3>Example:</h3>
 * <pre>{@code
 * ReactAgentExecutor executor = ReactAgentExecutor.builder()
 *     .reasoningFunction(ctx -> thinkAboutNextStep(ctx))
 *     .toolExecutor(action -> executeTool(action))
 *     .maxIterations(10)
 *     .timeout(Duration.ofSeconds(30))
 *     .build();
 *
 * ReactResult result = executor.execute("What is the weather in Sao Paulo?");
 * }</pre>
 */
public class ReactAgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(ReactAgentExecutor.class);

    private final Function<ReactContext, ReasoningResult> reasoningFunction;
    private final Function<Action, ObservationResult> toolExecutor;
    private final int maxIterations;
    private final Duration timeout;
    private final List<ReactStepListener> listeners;

    private ReactAgentExecutor(Builder builder) {
        this.reasoningFunction = Objects.requireNonNull(builder.reasoningFunction, "reasoningFunction is required");
        this.toolExecutor = Objects.requireNonNull(builder.toolExecutor, "toolExecutor is required");
        this.maxIterations = builder.maxIterations;
        this.timeout = builder.timeout;
        this.listeners = new ArrayList<>(builder.listeners);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Executes the ReAct loop for the given input.
     *
     * @param input The user query or task description
     * @return The final result after reasoning and acting
     */
    public ReactResult execute(String input) {
        return execute(input, Map.of());
    }

    /**
     * Executes the ReAct loop with additional context variables.
     *
     * @param input The user query or task description
     * @param variables Additional context variables
     * @return The final result
     */
    public ReactResult execute(String input, Map<String, Object> variables) {
        Instant startTime = Instant.now();
        ReactContext context = new ReactContext(input, new HashMap<>(variables));
        List<ReactStep> steps = new ArrayList<>();
        int iteration = 0;

        log.info("Starting ReAct loop for input: {}", truncate(input, 100));

        while (iteration < maxIterations) {
            // Check timeout
            if (Duration.between(startTime, Instant.now()).compareTo(timeout) > 0) {
                log.warn("ReAct loop timed out after {} iterations", iteration);
                return ReactResult.timeout(steps, iteration);
            }

            iteration++;
            final int currentIteration = iteration;
            log.debug("ReAct iteration {}/{}", currentIteration, maxIterations);

            // THOUGHT: Reason about the next step
            ReasoningResult reasoning;
            try {
                reasoning = reasoningFunction.apply(context);
            } catch (Exception e) {
                log.error("Reasoning failed at iteration {}", currentIteration, e);
                return ReactResult.error(steps, currentIteration, e);
            }

            notifyListeners(l -> l.onThought(currentIteration, reasoning));

            // Check if agent decided to finish
            if (reasoning.isFinalAnswer()) {
                log.info("ReAct loop completed with final answer after {} iterations", currentIteration);
                ReactStep finalStep = new ReactStep(currentIteration, reasoning, null, null);
                steps.add(finalStep);
                return ReactResult.success(steps, currentIteration, reasoning.getAnswer());
            }

            // ACT: Execute the selected tool/action
            Action action = reasoning.getAction();
            if (action == null) {
                log.warn("Reasoning returned no action and no final answer at iteration {}", currentIteration);
                return ReactResult.error(steps, currentIteration,
                        new IllegalStateException("No action or final answer from reasoning"));
            }

            notifyListeners(l -> l.onAction(currentIteration, action));

            ObservationResult observation;
            try {
                observation = toolExecutor.apply(action);
            } catch (Exception e) {
                log.error("Tool execution failed for action: {}", action.name(), e);
                observation = ObservationResult.error(action.name(), e.getMessage());
            }

            final ObservationResult finalObservation = observation;
            notifyListeners(l -> l.onObservation(currentIteration, finalObservation));

            // Record step
            ReactStep step = new ReactStep(iteration, reasoning, action, observation);
            steps.add(step);

            // OBSERVE: Feed observation back into context
            context.addStep(step);
        }

        log.warn("ReAct loop exhausted max iterations ({})", maxIterations);
        return ReactResult.maxIterationsReached(steps, maxIterations);
    }

    private void notifyListeners(java.util.function.Consumer<ReactStepListener> action) {
        for (ReactStepListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (Exception e) {
                log.warn("Listener notification failed", e);
            }
        }
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    // -------------------------------------------------------------------------
    // Inner classes
    // -------------------------------------------------------------------------

    /**
     * Context passed to the reasoning function at each iteration.
     */
    public static class ReactContext {
        private final String originalInput;
        private final Map<String, Object> variables;
        private final List<ReactStep> previousSteps;

        public ReactContext(String originalInput, Map<String, Object> variables) {
            this.originalInput = originalInput;
            this.variables = variables;
            this.previousSteps = new ArrayList<>();
        }

        public String getOriginalInput() { return originalInput; }
        public Map<String, Object> getVariables() { return variables; }
        public List<ReactStep> getPreviousSteps() { return Collections.unmodifiableList(previousSteps); }
        public int getIterationCount() { return previousSteps.size(); }

        void addStep(ReactStep step) {
            previousSteps.add(step);
        }
    }

    /**
     * Result of a single reasoning step.
     */
    public static class ReasoningResult {
        private final String thought;
        private final Action action;
        private final String answer;
        private final boolean finalAnswer;

        private ReasoningResult(String thought, Action action, String answer, boolean finalAnswer) {
            this.thought = thought;
            this.action = action;
            this.answer = answer;
            this.finalAnswer = finalAnswer;
        }

        public static ReasoningResult continueWithAction(String thought, Action action) {
            return new ReasoningResult(thought, action, null, false);
        }

        public static ReasoningResult finish(String thought, String answer) {
            return new ReasoningResult(thought, null, answer, true);
        }

        public String getThought() { return thought; }
        public Action getAction() { return action; }
        public String getAnswer() { return answer; }
        public boolean isFinalAnswer() { return finalAnswer; }
    }

    /**
     * Result of a tool execution (observation).
     */
    public static class ObservationResult {
        private final String toolName;
        private final String output;
        private final boolean success;
        private final Map<String, Object> metadata;

        private ObservationResult(String toolName, String output, boolean success, Map<String, Object> metadata) {
            this.toolName = toolName;
            this.output = output;
            this.success = success;
            this.metadata = metadata;
        }

        public static ObservationResult success(String toolName, String output) {
            return new ObservationResult(toolName, output, true, Map.of());
        }

        public static ObservationResult success(String toolName, String output, Map<String, Object> metadata) {
            return new ObservationResult(toolName, output, true, metadata);
        }

        public static ObservationResult error(String toolName, String errorMessage) {
            return new ObservationResult(toolName, errorMessage, false, Map.of());
        }

        public String getToolName() { return toolName; }
        public String getOutput() { return output; }
        public boolean isSuccess() { return success; }
        public Map<String, Object> getMetadata() { return metadata; }
    }

    /**
     * A single step in the ReAct loop (thought + action + observation).
     */
    public record ReactStep(
            int iteration,
            ReasoningResult reasoning,
            Action action,
            ObservationResult observation
    ) {}

    /**
     * Final result of the ReAct execution.
     */
    public static class ReactResult {
        public enum Status { SUCCESS, TIMEOUT, MAX_ITERATIONS, ERROR }

        private final Status status;
        private final List<ReactStep> steps;
        private final int totalIterations;
        private final String finalAnswer;
        private final Exception error;

        private ReactResult(Status status, List<ReactStep> steps, int totalIterations,
                            String finalAnswer, Exception error) {
            this.status = status;
            this.steps = List.copyOf(steps);
            this.totalIterations = totalIterations;
            this.finalAnswer = finalAnswer;
            this.error = error;
        }

        static ReactResult success(List<ReactStep> steps, int iterations, String answer) {
            return new ReactResult(Status.SUCCESS, steps, iterations, answer, null);
        }

        static ReactResult timeout(List<ReactStep> steps, int iterations) {
            return new ReactResult(Status.TIMEOUT, steps, iterations, null, null);
        }

        static ReactResult maxIterationsReached(List<ReactStep> steps, int iterations) {
            return new ReactResult(Status.MAX_ITERATIONS, steps, iterations, null, null);
        }

        static ReactResult error(List<ReactStep> steps, int iterations, Exception e) {
            return new ReactResult(Status.ERROR, steps, iterations, null, e);
        }

        public Status getStatus() { return status; }
        public List<ReactStep> getSteps() { return steps; }
        public int getTotalIterations() { return totalIterations; }
        public String getFinalAnswer() { return finalAnswer; }
        public Exception getError() { return error; }
        public boolean isSuccess() { return status == Status.SUCCESS; }
    }

    /**
     * Listener for ReAct loop events.
     */
    public interface ReactStepListener {
        default void onThought(int iteration, ReasoningResult reasoning) {}
        default void onAction(int iteration, Action action) {}
        default void onObservation(int iteration, ObservationResult observation) {}
    }

    /**
     * Builder for ReactAgentExecutor.
     */
    public static class Builder {
        private Function<ReactContext, ReasoningResult> reasoningFunction;
        private Function<Action, ObservationResult> toolExecutor;
        private int maxIterations = 10;
        private Duration timeout = Duration.ofSeconds(60);
        private final List<ReactStepListener> listeners = new ArrayList<>();

        public Builder reasoningFunction(Function<ReactContext, ReasoningResult> fn) {
            this.reasoningFunction = fn;
            return this;
        }

        public Builder toolExecutor(Function<Action, ObservationResult> fn) {
            this.toolExecutor = fn;
            return this;
        }

        public Builder maxIterations(int max) {
            this.maxIterations = max;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder addListener(ReactStepListener listener) {
            this.listeners.add(listener);
            return this;
        }

        public ReactAgentExecutor build() {
            return new ReactAgentExecutor(this);
        }
    }
}
