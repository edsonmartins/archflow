package br.com.archflow.agent.config;

/**
 * Configuração de retry para operações com falha
 */
public record RetryConfig(
    int maxAttempts,
    long initialDelay,
    double backoffMultiplier
) {
    public RetryConfig {
        if (maxAttempts < 0) {
            throw new IllegalArgumentException("maxAttempts must be >= 0");
        }
        if (initialDelay < 0) {
            throw new IllegalArgumentException("initialDelay must be >= 0");
        }
        if (backoffMultiplier <= 0) {
            throw new IllegalArgumentException("backoffMultiplier must be > 0");
        }
    }
}