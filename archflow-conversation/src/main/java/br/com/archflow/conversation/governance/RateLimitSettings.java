package br.com.archflow.conversation.governance;

/**
 * Limites de taxa por tenant (enforcement é responsabilidade do produto — segue
 * como follow-up no archflow).
 *
 * @since 1.0.0
 */
public record RateLimitSettings(
        int requestsPerMinute,
        int requestsPerHour,
        int maxConcurrent
) {
    public static RateLimitSettings defaults() {
        return new RateLimitSettings(60, 1000, 10);
    }
}
