package br.com.archflow.agent.orchestration;

import br.com.archflow.agent.confidence.ConfidenceResult;
import br.com.archflow.agent.confidence.ConfidenceScorer;
import br.com.archflow.agent.confidence.ScoringContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConfidenceVoterTest {

    @Test
    void refutesWhenConfidenceBelowThreshold() {
        ConfidenceScorer scorer = mock(ConfidenceScorer.class);
        when(scorer.score(any(ScoringContext.class)))
                .thenReturn(new ConfidenceResult(0.4, List.of(), true, "low"));

        ConfidenceVoter voter = new ConfidenceVoter(scorer, "is this finding real?");

        assertThat(voter.refutes("finding", "correctness")).isTrue();
    }

    @Test
    void doesNotRefuteWhenConfident() {
        ConfidenceScorer scorer = mock(ConfidenceScorer.class);
        when(scorer.score(any(ScoringContext.class)))
                .thenReturn(new ConfidenceResult(0.92, List.of(), false, "ok"));

        ConfidenceVoter voter = new ConfidenceVoter(scorer, "is this finding real?");

        assertThat(voter.refutes("finding", "security")).isFalse();
    }
}
