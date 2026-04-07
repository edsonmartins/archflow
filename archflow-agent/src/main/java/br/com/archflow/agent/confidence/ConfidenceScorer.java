package br.com.archflow.agent.confidence;

public interface ConfidenceScorer {
    ConfidenceResult score(ScoringContext context);
}
