package br.com.archflow.api.brainsentry.dto;

/**
 * Runtime-editable BrainSentry configuration.
 *
 * <p>Maps 1:1 to {@code br.com.archflow.brainsentry.BrainSentryConfig}
 * plus an {@code enabled} flag; the adapter is only wired when enabled
 * AND {@code baseUrl} is set.
 *
 * <p>{@code apiKey} is write-only in one direction — the GET endpoint
 * masks it to avoid leaking the secret to the browser.
 */
public record BrainSentryConfigDto(
        boolean enabled,
        String baseUrl,
        String apiKey,
        String tenantId,
        int maxTokenBudget,
        boolean deepAnalysisEnabled,
        long timeoutSeconds) {

    public BrainSentryConfigDto {
        if (maxTokenBudget <= 0) maxTokenBudget = 2000;
        if (timeoutSeconds <= 0) timeoutSeconds = 10;
    }

    public BrainSentryConfigDto withMaskedKey() {
        if (apiKey == null || apiKey.isBlank()) return this;
        String masked = apiKey.length() > 8
                ? apiKey.substring(0, 4) + "…" + apiKey.substring(apiKey.length() - 4)
                : "***";
        return new BrainSentryConfigDto(enabled, baseUrl, masked, tenantId,
                maxTokenBudget, deepAnalysisEnabled, timeoutSeconds);
    }
}
