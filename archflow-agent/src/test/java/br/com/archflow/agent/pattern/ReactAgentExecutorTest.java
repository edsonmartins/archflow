package br.com.archflow.agent.pattern;

import br.com.archflow.model.ai.domain.Action;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ReactAgentExecutor")
class ReactAgentExecutorTest {

    private Action makeAction(String name) {
        return new Action("tool", name, Map.of("query", "test"), false);
    }

    @Test
    @DisplayName("should complete successfully when reasoning returns final answer")
    void shouldCompleteWithFinalAnswer() {
        ReactAgentExecutor executor = ReactAgentExecutor.builder()
                .reasoningFunction(ctx -> ReactAgentExecutor.ReasoningResult.finish("I know the answer", "42"))
                .toolExecutor(action -> ReactAgentExecutor.ObservationResult.success(action.name(), "result"))
                .build();

        var result = executor.execute("What is the answer?");

        assertTrue(result.isSuccess());
        assertEquals("42", result.getFinalAnswer());
        assertEquals(1, result.getTotalIterations());
        assertEquals(ReactAgentExecutor.ReactResult.Status.SUCCESS, result.getStatus());
    }

    @Test
    @DisplayName("should execute tool and feed observation back")
    void shouldExecuteToolAndObserve() {
        AtomicInteger callCount = new AtomicInteger(0);

        ReactAgentExecutor executor = ReactAgentExecutor.builder()
                .reasoningFunction(ctx -> {
                    if (callCount.getAndIncrement() == 0) {
                        return ReactAgentExecutor.ReasoningResult.continueWithAction(
                                "I need to search", makeAction("search"));
                    }
                    // After observation, check context has previous step
                    assertEquals(1, ctx.getPreviousSteps().size());
                    return ReactAgentExecutor.ReasoningResult.finish("Got it", "Sao Paulo: 25C");
                })
                .toolExecutor(action -> ReactAgentExecutor.ObservationResult.success("search", "Temperature: 25C"))
                .build();

        var result = executor.execute("Weather in SP?");

        assertTrue(result.isSuccess());
        assertEquals(2, result.getTotalIterations());
        assertEquals("Sao Paulo: 25C", result.getFinalAnswer());
        assertEquals(1, result.getSteps().stream()
                .filter(s -> s.observation() != null).count());
    }

    @Test
    @DisplayName("should stop at max iterations")
    void shouldStopAtMaxIterations() {
        ReactAgentExecutor executor = ReactAgentExecutor.builder()
                .reasoningFunction(ctx -> ReactAgentExecutor.ReasoningResult.continueWithAction(
                        "Still searching", makeAction("search")))
                .toolExecutor(action -> ReactAgentExecutor.ObservationResult.success("search", "not found"))
                .maxIterations(3)
                .build();

        var result = executor.execute("Find something impossible");

        assertEquals(ReactAgentExecutor.ReactResult.Status.MAX_ITERATIONS, result.getStatus());
        assertFalse(result.isSuccess());
        assertEquals(3, result.getSteps().size());
    }

    @Test
    @DisplayName("should timeout when execution takes too long")
    void shouldTimeoutWhenTooSlow() {
        ReactAgentExecutor executor = ReactAgentExecutor.builder()
                .reasoningFunction(ctx -> {
                    try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return ReactAgentExecutor.ReasoningResult.continueWithAction("thinking", makeAction("slow"));
                })
                .toolExecutor(action -> ReactAgentExecutor.ObservationResult.success("slow", "done"))
                .maxIterations(100)
                .timeout(Duration.ofMillis(250))
                .build();

        var result = executor.execute("Slow task");

        assertEquals(ReactAgentExecutor.ReactResult.Status.TIMEOUT, result.getStatus());
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("should handle tool execution errors gracefully")
    void shouldHandleToolErrors() {
        AtomicInteger callCount = new AtomicInteger(0);

        ReactAgentExecutor executor = ReactAgentExecutor.builder()
                .reasoningFunction(ctx -> {
                    if (callCount.getAndIncrement() == 0) {
                        return ReactAgentExecutor.ReasoningResult.continueWithAction(
                                "try this", makeAction("failing-tool"));
                    }
                    // After error observation, should still continue
                    var lastObs = ctx.getPreviousSteps().get(0).observation();
                    assertFalse(lastObs.isSuccess());
                    return ReactAgentExecutor.ReasoningResult.finish("Tool failed, using fallback", "fallback answer");
                })
                .toolExecutor(action -> { throw new RuntimeException("Connection refused"); })
                .build();

        var result = executor.execute("Test error handling");

        assertTrue(result.isSuccess());
        assertEquals("fallback answer", result.getFinalAnswer());
    }

    @Test
    @DisplayName("should handle reasoning errors")
    void shouldHandleReasoningErrors() {
        ReactAgentExecutor executor = ReactAgentExecutor.builder()
                .reasoningFunction(ctx -> { throw new RuntimeException("LLM unavailable"); })
                .toolExecutor(action -> ReactAgentExecutor.ObservationResult.success("tool", "ok"))
                .build();

        var result = executor.execute("Test reasoning error");

        assertEquals(ReactAgentExecutor.ReactResult.Status.ERROR, result.getStatus());
        assertNotNull(result.getError());
    }

    @Test
    @DisplayName("should notify listeners on each step")
    void shouldNotifyListeners() {
        List<String> events = new ArrayList<>();
        AtomicInteger callCount = new AtomicInteger(0);

        ReactAgentExecutor executor = ReactAgentExecutor.builder()
                .reasoningFunction(ctx -> {
                    if (callCount.getAndIncrement() == 0) {
                        return ReactAgentExecutor.ReasoningResult.continueWithAction("thinking", makeAction("tool1"));
                    }
                    return ReactAgentExecutor.ReasoningResult.finish("done", "answer");
                })
                .toolExecutor(action -> ReactAgentExecutor.ObservationResult.success("tool1", "result"))
                .addListener(new ReactAgentExecutor.ReactStepListener() {
                    @Override
                    public void onThought(int iteration, ReactAgentExecutor.ReasoningResult r) {
                        events.add("thought:" + iteration);
                    }
                    @Override
                    public void onAction(int iteration, Action action) {
                        events.add("action:" + iteration + ":" + action.name());
                    }
                    @Override
                    public void onObservation(int iteration, ReactAgentExecutor.ObservationResult obs) {
                        events.add("observation:" + iteration + ":" + obs.isSuccess());
                    }
                })
                .build();

        executor.execute("Test listeners");

        assertEquals(List.of("thought:1", "action:1:tool1", "observation:1:true", "thought:2"), events);
    }

    @Test
    @DisplayName("should pass variables through context")
    void shouldPassVariables() {
        ReactAgentExecutor executor = ReactAgentExecutor.builder()
                .reasoningFunction(ctx -> {
                    assertEquals("pt-BR", ctx.getVariables().get("language"));
                    return ReactAgentExecutor.ReasoningResult.finish("ok", "done");
                })
                .toolExecutor(action -> ReactAgentExecutor.ObservationResult.success("tool", "ok"))
                .build();

        var result = executor.execute("Test", Map.of("language", "pt-BR"));

        assertTrue(result.isSuccess());
    }

    @Test
    @DisplayName("should return error when reasoning returns null action without final answer")
    void shouldErrorOnNullAction() {
        ReactAgentExecutor executor = ReactAgentExecutor.builder()
                .reasoningFunction(ctx ->
                        // Use continueWithAction with null to simulate broken reasoning
                        ReactAgentExecutor.ReasoningResult.continueWithAction("hmm", null))
                .toolExecutor(action -> ReactAgentExecutor.ObservationResult.success("tool", "ok"))
                .build();

        var result = executor.execute("Test null action");

        assertEquals(ReactAgentExecutor.ReactResult.Status.ERROR, result.getStatus());
    }

    @Test
    @DisplayName("should track multiple iterations with different tools")
    void shouldTrackMultipleIterations() {
        AtomicInteger callCount = new AtomicInteger(0);

        ReactAgentExecutor executor = ReactAgentExecutor.builder()
                .reasoningFunction(ctx -> {
                    int n = callCount.getAndIncrement();
                    if (n < 3) {
                        return ReactAgentExecutor.ReasoningResult.continueWithAction(
                                "step " + n, makeAction("tool_" + n));
                    }
                    return ReactAgentExecutor.ReasoningResult.finish("all done", "combined result");
                })
                .toolExecutor(action ->
                        ReactAgentExecutor.ObservationResult.success(action.name(), "result_" + action.name()))
                .maxIterations(10)
                .build();

        var result = executor.execute("Multi-step task");

        assertTrue(result.isSuccess());
        assertEquals(4, result.getTotalIterations());
        assertEquals(3, result.getSteps().stream().filter(s -> s.observation() != null).count());
    }
}
