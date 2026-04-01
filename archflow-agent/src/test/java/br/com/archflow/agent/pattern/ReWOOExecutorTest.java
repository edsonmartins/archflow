package br.com.archflow.agent.pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ReWOOExecutor")
class ReWOOExecutorTest {

    @Test
    @DisplayName("should plan and execute all tools in sequence")
    void shouldPlanAndExecuteAllTools() {
        List<String> executedTools = new ArrayList<>();

        ReWOOExecutor executor = ReWOOExecutor.builder()
                .plannerFunction(q -> List.of(
                        new ReWOOExecutor.ToolPlan("#E1", "search", Map.of("q", "AI")),
                        new ReWOOExecutor.ToolPlan("#E2", "summarize", Map.of("text", "content"))))
                .toolExecutor((name, args) -> {
                    executedTools.add(name);
                    return "result_" + name;
                })
                .synthesizer((q, evidences) -> "Final: " + evidences.size() + " evidences")
                .build();

        var result = executor.execute("Research AI");

        assertEquals(2, executedTools.size());
        assertEquals("search", executedTools.get(0));
        assertEquals("summarize", executedTools.get(1));
        assertEquals(2, result.plans().size());
    }

    @Test
    @DisplayName("should resolve placeholders from earlier results")
    void shouldResolvePlaceholders() {
        List<Map<String, Object>> capturedArgs = new ArrayList<>();

        ReWOOExecutor executor = ReWOOExecutor.builder()
                .plannerFunction(q -> List.of(
                        new ReWOOExecutor.ToolPlan("#E1", "search", Map.of("query", "Java frameworks")),
                        new ReWOOExecutor.ToolPlan("#E2", "analyze", Map.of("input", "#E1"))))
                .toolExecutor((name, args) -> {
                    capturedArgs.add(args);
                    if (name.equals("search")) return "LangChain4j, archflow";
                    return "analyzed: " + args.get("input");
                })
                .synthesizer((q, evidences) -> evidences.get("#E2"))
                .build();

        var result = executor.execute("Find frameworks");

        assertEquals(2, capturedArgs.size());
        // Second tool should have resolved #E1
        assertEquals("LangChain4j, archflow", capturedArgs.get(1).get("input"));
        assertEquals("analyzed: LangChain4j, archflow", result.answer());
    }

    @Test
    @DisplayName("should synthesize final answer from evidences")
    void shouldSynthesizeFinalAnswer() {
        ReWOOExecutor executor = ReWOOExecutor.builder()
                .plannerFunction(q -> List.of(
                        new ReWOOExecutor.ToolPlan("#E1", "tool1", Map.of())))
                .toolExecutor((name, args) -> "evidence_data")
                .synthesizer((query, evidences) -> {
                    assertEquals("What is X?", query);
                    assertEquals(1, evidences.size());
                    assertEquals("evidence_data", evidences.get("#E1"));
                    return "X is 42";
                })
                .build();

        var result = executor.execute("What is X?");

        assertEquals("X is 42", result.answer());
        assertEquals(1, result.evidences().size());
    }

    @Test
    @DisplayName("should handle tool execution errors gracefully")
    void shouldHandleToolErrors() {
        ReWOOExecutor executor = ReWOOExecutor.builder()
                .plannerFunction(q -> List.of(
                        new ReWOOExecutor.ToolPlan("#E1", "failing-tool", Map.of()),
                        new ReWOOExecutor.ToolPlan("#E2", "ok-tool", Map.of())))
                .toolExecutor((name, args) -> {
                    if (name.equals("failing-tool")) throw new RuntimeException("Connection refused");
                    return "ok";
                })
                .synthesizer((q, evidences) -> "synthesized")
                .build();

        var result = executor.execute("Test errors");

        // Should not throw, errors are captured
        assertNotNull(result.answer());
        assertTrue(result.evidences().get("#E1").startsWith("ERROR:"));
        assertEquals("ok", result.evidences().get("#E2"));
    }

    @Test
    @DisplayName("should use only 2 logical LLM calls (plan + synthesize)")
    void shouldUseOnly2LLMCalls() {
        AtomicInteger plannerCalls = new AtomicInteger(0);
        AtomicInteger synthesizerCalls = new AtomicInteger(0);

        ReWOOExecutor executor = ReWOOExecutor.builder()
                .plannerFunction(q -> {
                    plannerCalls.incrementAndGet();
                    return List.of(
                            new ReWOOExecutor.ToolPlan("#E1", "t1", Map.of()),
                            new ReWOOExecutor.ToolPlan("#E2", "t2", Map.of()),
                            new ReWOOExecutor.ToolPlan("#E3", "t3", Map.of()));
                })
                .toolExecutor((name, args) -> "result")
                .synthesizer((q, evidences) -> {
                    synthesizerCalls.incrementAndGet();
                    return "answer";
                })
                .build();

        executor.execute("Multi-tool query");

        assertEquals(1, plannerCalls.get(), "Planner should be called exactly once");
        assertEquals(1, synthesizerCalls.get(), "Synthesizer should be called exactly once");
    }

    @Test
    @DisplayName("should chain multiple placeholder resolutions")
    void shouldChainMultiplePlaceholders() {
        List<Map<String, Object>> capturedArgs = new ArrayList<>();

        ReWOOExecutor executor = ReWOOExecutor.builder()
                .plannerFunction(q -> List.of(
                        new ReWOOExecutor.ToolPlan("#E1", "fetch-name", Map.of()),
                        new ReWOOExecutor.ToolPlan("#E2", "fetch-age", Map.of()),
                        new ReWOOExecutor.ToolPlan("#E3", "combine", Map.of("text", "Name=#E1, Age=#E2"))))
                .toolExecutor((name, args) -> {
                    capturedArgs.add(args);
                    return switch (name) {
                        case "fetch-name" -> "Alice";
                        case "fetch-age" -> "30";
                        default -> "combined: " + args.get("text");
                    };
                })
                .synthesizer((q, evidences) -> evidences.get("#E3"))
                .build();

        var result = executor.execute("Get profile");

        // Third tool should have both placeholders resolved
        assertEquals("Name=Alice, Age=30", capturedArgs.get(2).get("text"));
        assertEquals("combined: Name=Alice, Age=30", result.answer());
    }
}
