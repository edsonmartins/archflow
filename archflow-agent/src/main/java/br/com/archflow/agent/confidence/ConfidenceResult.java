package br.com.archflow.agent.confidence;

import java.util.List;

public record ConfidenceResult(double score, List<ScoringFactor> factors, boolean shouldEscalate, String reason) {
    public record ScoringFactor(String name, double impact, String description) {}

    public static ConfidenceResult of(double score, List<ScoringFactor> factors, double threshold) {
        boolean escalate = score < threshold;
        String reason = escalate
                ? "Score %.2f below threshold %.2f".formatted(score, threshold)
                : "Score %.2f meets threshold %.2f".formatted(score, threshold);
        return new ConfidenceResult(Math.max(0, Math.min(1, score)), factors, escalate, reason);
    }
}
