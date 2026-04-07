package br.com.archflow.agent.confidence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@DisplayName("DefaultConfidenceScorer")
class DefaultConfidenceScorerTest {
    private DefaultConfidenceScorer scorer;

    @BeforeEach void setUp() { scorer = new DefaultConfidenceScorer(0.5); }

    @Test @DisplayName("should score high on success")
    void shouldScoreHigh() {
        var ctx = new ScoringContext("Your order #123 is being delivered today.",
                List.of(ScoringContext.ToolExecutionOutcome.success("tracking")), "where is my order", 5);
        var r = scorer.score(ctx);
        assertThat(r.score()).isGreaterThanOrEqualTo(0.8);
        assertThat(r.shouldEscalate()).isFalse();
    }

    @Test @DisplayName("should score low with no tools")
    void shouldScoreLowNoTools() {
        var ctx = new ScoringContext("I can help.", List.of(), "track my order", 3);
        assertThat(scorer.score(ctx).score()).isLessThanOrEqualTo(0.5);
    }

    @Test @DisplayName("should penalize tool failures")
    void shouldPenalizeFailures() {
        var ctx = new ScoringContext("Sorry, could not find.",
                List.of(ScoringContext.ToolExecutionOutcome.failure("tracking", "not found")), "order", 3);
        assertThat(scorer.score(ctx).factors()).anyMatch(f -> f.name().equals("tool_failures"));
    }

    @Test @DisplayName("should detect evasive phrases in Portuguese")
    void shouldDetectEvasive() {
        var ctx = new ScoringContext("Infelizmente não consigo acessar essa informação.",
                List.of(ScoringContext.ToolExecutionOutcome.success("t")), "pedido", 3);
        assertThat(scorer.score(ctx).factors()).anyMatch(f -> f.name().equals("evasive_phrase"));
    }

    @Test @DisplayName("should penalize empty response")
    void shouldPenalizeEmpty() {
        var ctx = new ScoringContext("", List.of(), "help", 1);
        var r = scorer.score(ctx);
        assertThat(r.score()).isLessThan(0.3);
        assertThat(r.shouldEscalate()).isTrue();
    }

    @Test @DisplayName("should escalate below threshold")
    void shouldEscalate() {
        var s = new DefaultConfidenceScorer(0.8);
        var ctx = new ScoringContext("I can try.", List.of(), "complaint", 10);
        assertThat(s.score(ctx).shouldEscalate()).isTrue();
    }

    @Test @DisplayName("should clamp score between 0 and 1")
    void shouldClamp() {
        var ctx = new ScoringContext("", List.of(
                ScoringContext.ToolExecutionOutcome.failure("a", "e"),
                ScoringContext.ToolExecutionOutcome.failure("b", "e"),
                ScoringContext.ToolExecutionOutcome.failure("c", "e")), "help", 1);
        var r = scorer.score(ctx);
        assertThat(r.score()).isBetween(0.0, 1.0);
    }

    @Test @DisplayName("should reward keyword overlap")
    void shouldRewardOverlap() {
        var ctx = new ScoringContext("Your order status is delivered.",
                List.of(ScoringContext.ToolExecutionOutcome.success("tracking")), "order status", 3);
        assertThat(scorer.score(ctx).factors()).anyMatch(f -> f.name().equals("keyword_coverage"));
    }
}
