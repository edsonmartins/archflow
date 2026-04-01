package br.com.archflow.agent.pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Chain-of-Thought with Self-Consistency (CoT-SC) strategy.
 *
 * <p>Samples multiple reasoning paths and uses majority voting to select
 * the most consistent answer. Achieves +17.9% accuracy on GSM8K with PaLM-540B.
 *
 * <p>Process:
 * <ol>
 *   <li>Generate N independent reasoning paths for the same question</li>
 *   <li>Extract the final answer from each path</li>
 *   <li>Select the answer with the highest vote count (majority voting)</li>
 * </ol>
 */
public class ChainOfThoughtStrategy {

    private static final Logger log = LoggerFactory.getLogger(ChainOfThoughtStrategy.class);

    private final Function<String, ReasoningPath> reasoningFunction;
    private final int numPaths;
    private final double temperatureVariation;

    private ChainOfThoughtStrategy(Builder builder) {
        this.reasoningFunction = Objects.requireNonNull(builder.reasoningFunction);
        this.numPaths = builder.numPaths;
        this.temperatureVariation = builder.temperatureVariation;
    }

    public static Builder builder() { return new Builder(); }

    public CoTResult reason(String question) {
        log.info("CoT-SC sampling {} paths for: {}", numPaths, question);
        List<ReasoningPath> paths = new ArrayList<>();

        for (int i = 0; i < numPaths; i++) {
            try {
                ReasoningPath path = reasoningFunction.apply(question);
                paths.add(path);
                log.debug("Path {}: answer={}", i + 1, path.answer());
            } catch (Exception e) {
                log.warn("Path {} failed: {}", i + 1, e.getMessage());
            }
        }

        if (paths.isEmpty()) {
            return new CoTResult(null, List.of(), Map.of(), 0);
        }

        // Majority voting
        Map<String, Long> votes = paths.stream()
                .collect(Collectors.groupingBy(
                        p -> p.answer().trim().toLowerCase(),
                        Collectors.counting()));

        String winner = votes.entrySet().stream()
                .max(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue)
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .orElse(null);

        // Find the original-cased answer
        String originalAnswer = paths.stream()
                .filter(p -> p.answer().trim().toLowerCase().equals(winner))
                .findFirst()
                .map(ReasoningPath::answer)
                .orElse(winner);

        double confidence = (double) votes.getOrDefault(winner, 0L) / paths.size();
        log.info("CoT-SC winner: '{}' with {}/{} votes ({}%)", originalAnswer, votes.get(winner), paths.size(), (int)(confidence * 100));

        return new CoTResult(originalAnswer, paths, votes, confidence);
    }

    // Inner types
    public record ReasoningPath(String reasoning, String answer) {}

    public record CoTResult(String answer, List<ReasoningPath> paths, Map<String, Long> votes, double confidence) {
        public boolean isConsensus() { return confidence >= 0.5; }
    }

    public static class Builder {
        private Function<String, ReasoningPath> reasoningFunction;
        private int numPaths = 5;
        private double temperatureVariation = 0.7;

        public Builder reasoningFunction(Function<String, ReasoningPath> fn) { this.reasoningFunction = fn; return this; }
        public Builder numPaths(int n) { this.numPaths = n; return this; }
        public Builder temperatureVariation(double t) { this.temperatureVariation = t; return this; }
        public ChainOfThoughtStrategy build() { return new ChainOfThoughtStrategy(this); }
    }
}
