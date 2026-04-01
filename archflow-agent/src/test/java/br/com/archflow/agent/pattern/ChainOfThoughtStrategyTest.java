package br.com.archflow.agent.pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ChainOfThoughtStrategy")
class ChainOfThoughtStrategyTest {

    @Test
    @DisplayName("should select majority answer")
    void shouldSelectMajorityAnswer() {
        AtomicInteger callCount = new AtomicInteger(0);

        ChainOfThoughtStrategy strategy = ChainOfThoughtStrategy.builder()
                .reasoningFunction(q -> {
                    int n = callCount.getAndIncrement();
                    if (n < 3) return new ChainOfThoughtStrategy.ReasoningPath("path " + n, "42");
                    return new ChainOfThoughtStrategy.ReasoningPath("path " + n, "43");
                })
                .numPaths(5)
                .build();

        var result = strategy.reason("What is 6 * 7?");

        assertEquals("42", result.answer());
        assertEquals(5, result.paths().size());
        assertEquals(3L, result.votes().get("42"));
        assertEquals(2L, result.votes().get("43"));
    }

    @Test
    @DisplayName("should calculate confidence as vote proportion")
    void shouldCalculateConfidenceAsVoteProportion() {
        AtomicInteger callCount = new AtomicInteger(0);

        ChainOfThoughtStrategy strategy = ChainOfThoughtStrategy.builder()
                .reasoningFunction(q -> {
                    int n = callCount.getAndIncrement();
                    if (n < 4) return new ChainOfThoughtStrategy.ReasoningPath("path", "yes");
                    return new ChainOfThoughtStrategy.ReasoningPath("path", "no");
                })
                .numPaths(5)
                .build();

        var result = strategy.reason("Is Java great?");

        assertEquals(0.8, result.confidence(), 0.001);
        assertTrue(result.isConsensus());
    }

    @Test
    @DisplayName("should handle all different answers")
    void shouldHandleAllDifferentAnswers() {
        AtomicInteger callCount = new AtomicInteger(0);

        ChainOfThoughtStrategy strategy = ChainOfThoughtStrategy.builder()
                .reasoningFunction(q -> {
                    int n = callCount.getAndIncrement();
                    return new ChainOfThoughtStrategy.ReasoningPath("path " + n, "answer_" + n);
                })
                .numPaths(3)
                .build();

        var result = strategy.reason("Ambiguous question?");

        assertNotNull(result.answer());
        assertEquals(3, result.paths().size());
        // Each answer has 1 vote, confidence = 1/3
        assertEquals(1.0 / 3.0, result.confidence(), 0.001);
        assertFalse(result.isConsensus());
    }

    @Test
    @DisplayName("should handle reasoning function failures gracefully")
    void shouldHandleReasoningFailures() {
        AtomicInteger callCount = new AtomicInteger(0);

        ChainOfThoughtStrategy strategy = ChainOfThoughtStrategy.builder()
                .reasoningFunction(q -> {
                    int n = callCount.getAndIncrement();
                    if (n == 1 || n == 3) throw new RuntimeException("LLM error");
                    return new ChainOfThoughtStrategy.ReasoningPath("ok path", "42");
                })
                .numPaths(5)
                .build();

        var result = strategy.reason("Flaky question?");

        // 3 out of 5 succeed, all with answer "42"
        assertEquals("42", result.answer());
        assertEquals(3, result.paths().size());
        assertEquals(1.0, result.confidence(), 0.001);
    }

    @Test
    @DisplayName("should identify consensus when >= 50% agree")
    void shouldIdentifyConsensus() {
        AtomicInteger callCount = new AtomicInteger(0);

        ChainOfThoughtStrategy strategy = ChainOfThoughtStrategy.builder()
                .reasoningFunction(q -> {
                    int n = callCount.getAndIncrement();
                    if (n < 3) return new ChainOfThoughtStrategy.ReasoningPath("path", "A");
                    if (n < 5) return new ChainOfThoughtStrategy.ReasoningPath("path", "B");
                    return new ChainOfThoughtStrategy.ReasoningPath("path", "C");
                })
                .numPaths(6)
                .build();

        var result = strategy.reason("Multiple choice?");

        assertEquals("A", result.answer());
        assertTrue(result.isConsensus()); // 3/6 = 0.5, exactly at threshold
        assertEquals(0.5, result.confidence(), 0.001);
    }
}
