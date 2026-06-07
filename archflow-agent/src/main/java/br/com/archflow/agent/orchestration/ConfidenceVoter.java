package br.com.archflow.agent.orchestration;

import br.com.archflow.agent.confidence.ConfidenceResult;
import br.com.archflow.agent.confidence.ConfidenceScorer;
import br.com.archflow.agent.confidence.ScoringContext;
import br.com.archflow.orchestration.Voter;

import java.util.List;

/**
 * Orchestration {@link Voter} backed by the {@link ConfidenceScorer}: refutes a
 * finding when its confidence falls below the configured escalation threshold
 * (ADR-0002 D4 / design-0003).
 *
 * <p>Note: the confidence scorer is deterministic for a given input, so K
 * {@code ConfidenceVoter}s vote identically — this adapter is a confidence
 * <em>gate</em> (use it with {@code voters = 1}). True adversarial diversity
 * (independent model samples per lens) is a separate, LLM-backed voter.
 */
public final class ConfidenceVoter implements Voter<String> {

    private final ConfidenceScorer scorer;
    private final String userQuery;

    public ConfidenceVoter(ConfidenceScorer scorer, String userQuery) {
        this.scorer = scorer;
        this.userQuery = userQuery == null ? "" : userQuery;
    }

    @Override
    public boolean refutes(String finding, String lens) {
        ScoringContext context = new ScoringContext(
                finding,
                List.of(),
                userQuery + (lens == null || lens.isBlank() ? "" : " [" + lens + "]"),
                0);
        ConfidenceResult result = scorer.score(context);
        // Low confidence (scorer flags escalation) => refute the finding.
        return result.shouldEscalate();
    }
}
