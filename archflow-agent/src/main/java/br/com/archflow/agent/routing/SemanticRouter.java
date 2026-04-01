package br.com.archflow.agent.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;

/**
 * Routes requests to handlers based on semantic similarity.
 *
 * <p>Uses embedding vectors and cosine similarity to match incoming
 * queries to predefined routes. Falls back to an LLM-based classifier
 * when confidence is below threshold.
 *
 * <p>Three routing strategies:
 * <ul>
 *   <li><b>Semantic</b>: Embedding-based cosine similarity (fast, no LLM call)</li>
 *   <li><b>LLM</b>: Classifier with structured output (flexible, adds latency)</li>
 *   <li><b>Hybrid</b>: Semantic first, LLM fallback when confidence is low</li>
 * </ul>
 *
 * <h3>Example:</h3>
 * <pre>{@code
 * SemanticRouter router = SemanticRouter.builder()
 *     .embeddingFunction(text -> embedModel.embed(text))
 *     .addRoute("billing", "Payment, invoice, charges", billingHandler)
 *     .addRoute("technical", "Bug, error, crash, not working", techHandler)
 *     .confidenceThreshold(0.7)
 *     .build();
 *
 * RoutingResult result = router.route("My payment failed");
 * result.getHandler().accept(context);
 * }</pre>
 */
public class SemanticRouter {

    private static final Logger log = LoggerFactory.getLogger(SemanticRouter.class);

    private final List<Route> routes;
    private final Function<String, float[]> embeddingFunction;
    private final Function<String, String> llmClassifier;
    private final double confidenceThreshold;
    private final RoutingStrategy strategy;

    private SemanticRouter(Builder builder) {
        this.routes = List.copyOf(builder.routes);
        this.embeddingFunction = builder.embeddingFunction;
        this.llmClassifier = builder.llmClassifier;
        this.confidenceThreshold = builder.confidenceThreshold;
        this.strategy = builder.strategy;

        // Pre-compute embeddings for all routes
        if (embeddingFunction != null) {
            for (Route route : routes) {
                if (route.embedding == null) {
                    route.embedding = embeddingFunction.apply(route.description);
                }
            }
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Routes a query to the best matching handler.
     *
     * @param query The input query to route
     * @return The routing result with matched route and confidence
     */
    public RoutingResult route(String query) {
        log.debug("Routing query: {}", query);

        return switch (strategy) {
            case SEMANTIC -> routeSemantic(query);
            case LLM -> routeLlm(query);
            case HYBRID -> routeHybrid(query);
        };
    }

    private RoutingResult routeSemantic(String query) {
        if (embeddingFunction == null) {
            return RoutingResult.noMatch("No embedding function configured");
        }

        float[] queryEmbedding = embeddingFunction.apply(query);
        Route bestRoute = null;
        double bestScore = -1;

        for (Route route : routes) {
            if (route.embedding != null) {
                double similarity = cosineSimilarity(queryEmbedding, route.embedding);
                if (similarity > bestScore) {
                    bestScore = similarity;
                    bestRoute = route;
                }
            }
        }

        if (bestRoute != null && bestScore >= confidenceThreshold) {
            log.debug("Semantic match: {} (score={})", bestRoute.name, bestScore);
            return RoutingResult.matched(bestRoute.name, bestScore, RoutingStrategy.SEMANTIC);
        }

        return RoutingResult.noMatch("Below confidence threshold: " + bestScore);
    }

    private RoutingResult routeLlm(String query) {
        if (llmClassifier == null) {
            return RoutingResult.noMatch("No LLM classifier configured");
        }

        String classification = llmClassifier.apply(query);
        return routes.stream()
                .filter(r -> r.name.equalsIgnoreCase(classification))
                .findFirst()
                .map(r -> RoutingResult.matched(r.name, 1.0, RoutingStrategy.LLM))
                .orElse(RoutingResult.noMatch("LLM classification not matched: " + classification));
    }

    private RoutingResult routeHybrid(String query) {
        RoutingResult semantic = routeSemantic(query);
        if (semantic.isMatched() && semantic.getConfidence() >= confidenceThreshold) {
            return semantic;
        }

        log.debug("Semantic confidence too low ({}), falling back to LLM", semantic.getConfidence());
        return routeLlm(query);
    }

    /**
     * Gets all registered route names.
     */
    public List<String> getRouteNames() {
        return routes.stream().map(r -> r.name).toList();
    }

    public static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vectors must have same dimension");
        }
        double dotProduct = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        return denominator == 0 ? 0 : dotProduct / denominator;
    }

    // -------------------------------------------------------------------------
    // Inner classes
    // -------------------------------------------------------------------------

    public enum RoutingStrategy {
        SEMANTIC, LLM, HYBRID
    }

    static class Route {
        final String name;
        final String description;
        volatile float[] embedding;

        Route(String name, String description) {
            this.name = name;
            this.description = description;
        }
    }

    /**
     * Result of a routing decision.
     */
    public static class RoutingResult {
        private final String routeName;
        private final double confidence;
        private final RoutingStrategy resolvedBy;
        private final boolean matched;
        private final String reason;

        private RoutingResult(String routeName, double confidence, RoutingStrategy resolvedBy, boolean matched, String reason) {
            this.routeName = routeName;
            this.confidence = confidence;
            this.resolvedBy = resolvedBy;
            this.matched = matched;
            this.reason = reason;
        }

        public static RoutingResult matched(String routeName, double confidence, RoutingStrategy resolvedBy) {
            return new RoutingResult(routeName, confidence, resolvedBy, true, null);
        }

        public static RoutingResult noMatch(String reason) {
            return new RoutingResult(null, 0, null, false, reason);
        }

        public String getRouteName() { return routeName; }
        public double getConfidence() { return confidence; }
        public RoutingStrategy getResolvedBy() { return resolvedBy; }
        public boolean isMatched() { return matched; }
        public String getReason() { return reason; }
    }

    /**
     * Builder for SemanticRouter.
     */
    public static class Builder {
        private final List<Route> routes = new ArrayList<>();
        private Function<String, float[]> embeddingFunction;
        private Function<String, String> llmClassifier;
        private double confidenceThreshold = 0.7;
        private RoutingStrategy strategy = RoutingStrategy.HYBRID;

        public Builder embeddingFunction(Function<String, float[]> fn) {
            this.embeddingFunction = fn;
            return this;
        }

        public Builder llmClassifier(Function<String, String> fn) {
            this.llmClassifier = fn;
            return this;
        }

        public Builder addRoute(String name, String description) {
            this.routes.add(new Route(name, description));
            return this;
        }

        public Builder confidenceThreshold(double threshold) {
            this.confidenceThreshold = threshold;
            return this;
        }

        public Builder strategy(RoutingStrategy strategy) {
            this.strategy = strategy;
            return this;
        }

        public SemanticRouter build() {
            return new SemanticRouter(this);
        }
    }
}
