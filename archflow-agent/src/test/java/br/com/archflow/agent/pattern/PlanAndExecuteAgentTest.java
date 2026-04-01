package br.com.archflow.agent.pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PlanAndExecuteAgent")
class PlanAndExecuteAgentTest {

    private PlanAndExecuteAgent.PlanStep step(int order, String desc) {
        return new PlanAndExecuteAgent.PlanStep(order, desc, "action", Map.of());
    }

    private PlanAndExecuteAgent.PlanStep step(int order, String desc, Map<String, Object> params) {
        return new PlanAndExecuteAgent.PlanStep(order, desc, "action", params);
    }

    @Test
    @DisplayName("should create plan and execute all steps successfully")
    void shouldExecuteAllStepsSuccessfully() {
        PlanAndExecuteAgent agent = PlanAndExecuteAgent.builder()
                .planner(task -> List.of(step(1, "search"), step(2, "analyze"), step(3, "summarize")))
                .executor(s -> PlanAndExecuteAgent.StepResult.ok("done: " + s.description()))
                .build();

        var result = agent.execute("Research AI agents");

        assertTrue(result.success());
        assertEquals(3, result.stepResults().size());
        assertNull(result.error());
        assertEquals(0, result.replanCount());
    }

    @Test
    @DisplayName("should handle step failure without replanner")
    void shouldHandleStepFailureWithoutReplanner() {
        PlanAndExecuteAgent agent = PlanAndExecuteAgent.builder()
                .planner(task -> List.of(step(1, "will-fail"), step(2, "never-reached")))
                .executor(s -> PlanAndExecuteAgent.StepResult.fail("something broke"))
                .build();

        var result = agent.execute("Failing task");

        assertFalse(result.success());
        assertEquals(1, result.stepResults().size());
        assertNotNull(result.error());
        assertTrue(result.error().contains("will-fail"));
    }

    @Test
    @DisplayName("should replan when step fails")
    void shouldReplanWhenStepFails() {
        AtomicInteger planCount = new AtomicInteger(0);

        PlanAndExecuteAgent agent = PlanAndExecuteAgent.builder()
                .planner(task -> {
                    planCount.incrementAndGet();
                    return List.of(step(1, "fragile-step"), step(2, "final-step"));
                })
                .executor(s -> {
                    if (s.description().equals("fragile-step") && planCount.get() == 1) {
                        return PlanAndExecuteAgent.StepResult.fail("failed first time");
                    }
                    return PlanAndExecuteAgent.StepResult.ok("done");
                })
                .replanner(ctx -> {
                    assertEquals(0, ctx.failedAtIndex());
                    return List.of(step(1, "robust-step"), step(2, "final-step"));
                })
                .maxReplans(2)
                .build();

        var result = agent.execute("Task with replan");

        assertTrue(result.success());
        assertEquals(1, result.replanCount());
        assertEquals(2, result.stepResults().size());
    }

    @Test
    @DisplayName("should respect max replans limit")
    void shouldRespectMaxReplansLimit() {
        PlanAndExecuteAgent agent = PlanAndExecuteAgent.builder()
                .planner(task -> List.of(step(1, "always-fails")))
                .executor(s -> PlanAndExecuteAgent.StepResult.fail("always fails"))
                .replanner(ctx -> List.of(step(1, "still-fails")))
                .maxReplans(2)
                .build();

        var result = agent.execute("Doomed task");

        assertFalse(result.success());
        // After 2 replans, the 3rd failure should not trigger another replan
        assertEquals(2, result.replanCount());
    }

    @Test
    @DisplayName("should pass step parameters to executor")
    void shouldPassStepParametersToExecutor() {
        List<Map<String, Object>> receivedParams = new ArrayList<>();

        PlanAndExecuteAgent agent = PlanAndExecuteAgent.builder()
                .planner(task -> List.of(
                        step(1, "search", Map.of("query", "AI agents", "limit", 10)),
                        step(2, "filter", Map.of("category", "research"))))
                .executor(s -> {
                    receivedParams.add(s.parameters());
                    return PlanAndExecuteAgent.StepResult.ok("ok");
                })
                .build();

        agent.execute("Parameterized task");

        assertEquals(2, receivedParams.size());
        assertEquals("AI agents", receivedParams.get(0).get("query"));
        assertEquals(10, receivedParams.get(0).get("limit"));
        assertEquals("research", receivedParams.get(1).get("category"));
    }

    @Test
    @DisplayName("should report replan count in result")
    void shouldReportReplanCountInResult() {
        AtomicInteger executionCount = new AtomicInteger(0);

        PlanAndExecuteAgent agent = PlanAndExecuteAgent.builder()
                .planner(task -> List.of(step(1, "step")))
                .executor(s -> {
                    int count = executionCount.getAndIncrement();
                    if (count < 2) {
                        return PlanAndExecuteAgent.StepResult.fail("not yet");
                    }
                    return PlanAndExecuteAgent.StepResult.ok("finally");
                })
                .replanner(ctx -> List.of(step(1, "retry-step")))
                .maxReplans(3)
                .build();

        var result = agent.execute("Task needing multiple replans");

        assertTrue(result.success());
        assertEquals(2, result.replanCount());
    }
}
