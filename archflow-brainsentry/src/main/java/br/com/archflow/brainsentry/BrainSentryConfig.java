package br.com.archflow.brainsentry;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for Brain Sentry integration.
 *
 * @param baseUrl Brain Sentry API base URL (e.g., "http://localhost:8081/api")
 * @param apiKey Authentication API key
 * @param tenantId Tenant identifier for multi-tenancy
 * @param maxTokenBudget Maximum tokens for context injection (default 2000)
 * @param deepAnalysisEnabled Whether to force deep LLM analysis on interception
 * @param timeout HTTP request timeout
 */
public record BrainSentryConfig(
        String baseUrl,
        String apiKey,
        String tenantId,
        int maxTokenBudget,
        boolean deepAnalysisEnabled,
        Duration timeout
) {
    public BrainSentryConfig {
        Objects.requireNonNull(baseUrl, "baseUrl is required");
        if (maxTokenBudget <= 0) maxTokenBudget = 2000;
        if (timeout == null) timeout = Duration.ofSeconds(10);
    }

    public static BrainSentryConfig of(String baseUrl) {
        return new BrainSentryConfig(baseUrl, null, null, 2000, false, null);
    }

    public static BrainSentryConfig of(String baseUrl, String apiKey, String tenantId) {
        return new BrainSentryConfig(baseUrl, apiKey, tenantId, 2000, false, null);
    }
}
