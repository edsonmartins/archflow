package br.com.archflow.agent.pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ReWOOExecutor")
class ReWOOExecutorTest {

    @Test
    void executesPlansAndSynthesizes() {
        var executor = ReWOOExecutor.builder()
                .plannerFunction(query -> List.of(
                        new ReWOOExecutor.ToolPlan("#E1", "search", Map.of("q", query)),
                        new ReWOOExecutor.ToolPlan("#E2", "summarize", Map.of("text", "#E1"))))
                .toolExecutor((toolName, args) -> "result for " + toolName)
                .synthesizer((query, evidences) ->
                        "Answer: " + evidences.get("#E1") + " + " + evidences.get("#E2"))
                .build();

        ReWOOExecutor.ReWOOResult result = executor.execute("What is Java?");

        assertThat(result.answer()).contains("result for search");
        assertThat(result.answer()).contains("result for summarize");
        assertThat(result.plans()).hasSize(2);
        assertThat(result.evidences()).containsKeys("#E1", "#E2");
    }

    @Test
    void singlePlanSingleEvidence() {
        var executor = ReWOOExecutor.builder()
                .plannerFunction(q -> List.of(new ReWOOExecutor.ToolPlan("#E1", "lookup", Map.of())))
                .toolExecutor((name, args) -> "found it")
                .synthesizer((q, ev) -> ev.get("#E1"))
                .build();

        var result = executor.execute("find it");
        assertThat(result.answer()).isEqualTo("found it");
        assertThat(result.evidences()).hasSize(1);
    }

    @Test
    void emptyPlansProducesEmptyEvidences() {
        var executor = ReWOOExecutor.builder()
                .plannerFunction(q -> List.of())
                .toolExecutor((name, args) -> "x")
                .synthesizer((q, ev) -> "no evidence")
                .build();

        var result = executor.execute("nothing");
        assertThat(result.plans()).isEmpty();
        assertThat(result.evidences()).isEmpty();
        assertThat(result.answer()).isEqualTo("no evidence");
    }

    @Test
    void builderRequiresAllFunctions() {
        assertThatThrownBy(() -> ReWOOExecutor.builder().build())
                .isInstanceOf(Exception.class);
    }
}
