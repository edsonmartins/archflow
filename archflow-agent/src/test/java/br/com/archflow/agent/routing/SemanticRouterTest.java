package br.com.archflow.agent.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SemanticRouter")
class SemanticRouterTest {

    // Simple mock embedding: just returns fixed vectors based on content
    private float[] mockEmbed(String text) {
        if (text.toLowerCase().contains("billing") || text.toLowerCase().contains("payment")) {
            return new float[]{1.0f, 0.0f, 0.0f};
        } else if (text.toLowerCase().contains("technical") || text.toLowerCase().contains("bug") || text.toLowerCase().contains("error")) {
            return new float[]{0.0f, 1.0f, 0.0f};
        } else if (text.toLowerCase().contains("shipping") || text.toLowerCase().contains("delivery")) {
            return new float[]{0.0f, 0.0f, 1.0f};
        }
        return new float[]{0.33f, 0.33f, 0.33f};
    }

    @Test
    @DisplayName("should route to best semantic match")
    void shouldRouteToSemanticMatch() {
        SemanticRouter router = SemanticRouter.builder()
                .embeddingFunction(this::mockEmbed)
                .addRoute("billing", "Payment, invoice, billing charges")
                .addRoute("technical", "Bug, error, technical issue")
                .addRoute("shipping", "Delivery, shipping, tracking")
                .confidenceThreshold(0.8)
                .strategy(SemanticRouter.RoutingStrategy.SEMANTIC)
                .build();

        var result = router.route("My payment failed");

        assertTrue(result.isMatched());
        assertEquals("billing", result.getRouteName());
        assertTrue(result.getConfidence() > 0.8);
    }

    @Test
    @DisplayName("should return no match below threshold")
    void shouldReturnNoMatchBelowThreshold() {
        SemanticRouter router = SemanticRouter.builder()
                .embeddingFunction(this::mockEmbed)
                .addRoute("billing", "Payment, invoice, billing charges")
                .confidenceThreshold(0.99)
                .strategy(SemanticRouter.RoutingStrategy.SEMANTIC)
                .build();

        var result = router.route("something vague and unrelated");

        assertFalse(result.isMatched());
    }

    @Test
    @DisplayName("should fallback to LLM in hybrid mode")
    void shouldFallbackToLlm() {
        SemanticRouter router = SemanticRouter.builder()
                .embeddingFunction(this::mockEmbed)
                .llmClassifier(query -> "billing")
                .addRoute("billing", "Payment, invoice, billing charges")
                .confidenceThreshold(0.99) // Force semantic to fail
                .strategy(SemanticRouter.RoutingStrategy.HYBRID)
                .build();

        var result = router.route("something ambiguous");

        assertTrue(result.isMatched());
        assertEquals("billing", result.getRouteName());
        assertEquals(SemanticRouter.RoutingStrategy.LLM, result.getResolvedBy());
    }

    @Test
    @DisplayName("should use LLM-only strategy")
    void shouldUseLlmOnly() {
        SemanticRouter router = SemanticRouter.builder()
                .llmClassifier(query -> "technical")
                .addRoute("billing", "Payments")
                .addRoute("technical", "Tech issues")
                .strategy(SemanticRouter.RoutingStrategy.LLM)
                .build();

        var result = router.route("My app is crashing");

        assertTrue(result.isMatched());
        assertEquals("technical", result.getRouteName());
    }

    @Test
    @DisplayName("should return no match when LLM classification doesn't match any route")
    void shouldReturnNoMatchForUnknownLlmClassification() {
        SemanticRouter router = SemanticRouter.builder()
                .llmClassifier(query -> "unknown_category")
                .addRoute("billing", "Payments")
                .strategy(SemanticRouter.RoutingStrategy.LLM)
                .build();

        var result = router.route("Something weird");

        assertFalse(result.isMatched());
    }

    @Test
    @DisplayName("should list all route names")
    void shouldListRouteNames() {
        SemanticRouter router = SemanticRouter.builder()
                .addRoute("billing", "Payments")
                .addRoute("technical", "Tech")
                .addRoute("shipping", "Delivery")
                .strategy(SemanticRouter.RoutingStrategy.LLM)
                .build();

        assertEquals(3, router.getRouteNames().size());
        assertTrue(router.getRouteNames().containsAll(java.util.List.of("billing", "technical", "shipping")));
    }

    @Test
    @DisplayName("cosine similarity should return 1 for identical vectors")
    void cosineSimilarityIdentical() {
        float[] v = {1, 2, 3};
        assertEquals(1.0, SemanticRouter.cosineSimilarity(v, v), 0.001);
    }

    @Test
    @DisplayName("cosine similarity should return 0 for orthogonal vectors")
    void cosineSimilarityOrthogonal() {
        float[] a = {1, 0, 0};
        float[] b = {0, 1, 0};
        assertEquals(0.0, SemanticRouter.cosineSimilarity(a, b), 0.001);
    }
}
