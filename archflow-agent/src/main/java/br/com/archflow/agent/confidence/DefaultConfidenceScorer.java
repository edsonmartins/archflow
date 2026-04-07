package br.com.archflow.agent.confidence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.regex.Pattern;

public class DefaultConfidenceScorer implements ConfidenceScorer {
    private static final Logger log = LoggerFactory.getLogger(DefaultConfidenceScorer.class);
    private static final double BASE_SCORE = 0.7;
    private static final List<Pattern> EVASIVE_PATTERNS = List.of(
            Pattern.compile("(?i)n[aã]o (tenho|possuo|consigo).*informa"),
            Pattern.compile("(?i)n[aã]o (sei|é poss[ií]vel)"),
            Pattern.compile("(?i)infelizmente.*n[aã]o"),
            Pattern.compile("(?i)desculp[ea].*n[aã]o.*consigo"),
            Pattern.compile("(?i)I('m| am) (not able|unable|sorry)"));

    private final double escalationThreshold;

    public DefaultConfidenceScorer(double escalationThreshold) { this.escalationThreshold = escalationThreshold; }
    public DefaultConfidenceScorer() { this(0.5); }

    @Override
    public ConfidenceResult score(ScoringContext context) {
        double score = BASE_SCORE;
        List<ConfidenceResult.ScoringFactor> factors = new ArrayList<>();

        if (context.toolResults().isEmpty()) {
            score -= 0.3;
            factors.add(new ConfidenceResult.ScoringFactor("no_tools", -0.3, "No tools were executed"));
        } else {
            long failures = context.toolResults().stream().filter(t -> !t.success()).count();
            long successes = context.toolResults().stream().filter(ScoringContext.ToolExecutionOutcome::success).count();
            if (failures > 0) {
                double penalty = -0.2 * failures;
                score += penalty;
                factors.add(new ConfidenceResult.ScoringFactor("tool_failures", penalty, failures + " tool(s) failed"));
            }
            if (successes > 0 && failures == 0) {
                score += 0.2;
                factors.add(new ConfidenceResult.ScoringFactor("all_tools_success", 0.2, successes + " tool(s) succeeded"));
            }
        }

        String response = context.response();
        if (response == null || response.isBlank()) {
            score -= 0.4;
            factors.add(new ConfidenceResult.ScoringFactor("empty_response", -0.4, "Response is empty"));
        } else if (response.length() < 20) {
            score -= 0.2;
            factors.add(new ConfidenceResult.ScoringFactor("short_response", -0.2, "Response is very short"));
        }

        if (response != null && EVASIVE_PATTERNS.stream().anyMatch(p -> p.matcher(response).find())) {
            score -= 0.3;
            factors.add(new ConfidenceResult.ScoringFactor("evasive_phrase", -0.3, "Evasive language detected"));
        }

        if (response != null && context.userQuery() != null) {
            Set<String> qWords = Set.of(context.userQuery().toLowerCase().split("\\W+"));
            Set<String> rWords = Set.of(response.toLowerCase().split("\\W+"));
            long overlap = qWords.stream().filter(rWords::contains).count();
            if (overlap >= Math.min(2, qWords.size())) {
                score += 0.1;
                factors.add(new ConfidenceResult.ScoringFactor("keyword_coverage", 0.1, "Response addresses query"));
            }
        }

        score = Math.max(0.0, Math.min(1.0, score));
        log.debug("Confidence score: {} (factors: {})", score, factors.size());
        return ConfidenceResult.of(score, factors, escalationThreshold);
    }
}
