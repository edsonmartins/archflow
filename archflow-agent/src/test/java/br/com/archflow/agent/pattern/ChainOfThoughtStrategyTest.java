package br.com.archflow.agent.pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ChainOfThoughtStrategy")
class ChainOfThoughtStrategyTest {

    @Test
    void majorityVoteSelectsMostCommonAnswer() {
        var strategy = ChainOfThoughtStrategy.builder()
                .reasoningFunction(q -> new ChainOfThoughtStrategy.ReasoningPath(
                        "Thinking about " + q, "42"))
                .numPaths(5)
                .build();

        var result = strategy.reason("What is 6*7?");

        assertThat(result.answer()).isEqualTo("42");
        assertThat(result.paths()).hasSize(5);
        assertThat(result.votes()).containsEntry("42", 5L);
        assertThat(result.confidence()).isEqualTo(1.0);
    }

    @Test
    void mixedAnswersLowerConfidence() {
        final int[] call = {0};
        var strategy = ChainOfThoughtStrategy.builder()
                .reasoningFunction(q -> {
                    call[0]++;
                    String answer = call[0] <= 2 ? "yes" : "no";
                    return new ChainOfThoughtStrategy.ReasoningPath("reason " + call[0], answer);
                })
                .numPaths(4)
                .build();

        var result = strategy.reason("Is Java good?");

        assertThat(result.paths()).hasSize(4);
        assertThat(result.votes()).containsKeys("yes", "no");
        assertThat(result.confidence()).isLessThan(1.0);
    }

    @Test
    void singlePathIsAlwaysConfident() {
        var strategy = ChainOfThoughtStrategy.builder()
                .reasoningFunction(q -> new ChainOfThoughtStrategy.ReasoningPath("thought", "answer"))
                .numPaths(1)
                .build();

        var result = strategy.reason("q");
        assertThat(result.paths()).hasSize(1);
        assertThat(result.confidence()).isEqualTo(1.0);
    }

    @Test
    void builderRequiresReasoningFunction() {
        assertThatThrownBy(() -> ChainOfThoughtStrategy.builder().build())
                .isInstanceOf(Exception.class);
    }

    @Test
    void temperatureVariationIsAccepted() {
        var strategy = ChainOfThoughtStrategy.builder()
                .reasoningFunction(q -> new ChainOfThoughtStrategy.ReasoningPath("r", "a"))
                .numPaths(3)
                .temperatureVariation(0.5)
                .build();

        var result = strategy.reason("q");
        assertThat(result.answer()).isEqualTo("a");
    }
}
