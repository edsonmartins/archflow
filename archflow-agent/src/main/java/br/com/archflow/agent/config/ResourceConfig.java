package br.com.archflow.agent.config;

/**
 * Configuração de recursos do agent
 */
public record ResourceConfig(
    int maxThreads,
    long maxMemory
) {
    public ResourceConfig {
        if (maxThreads <= 0) {
            throw new IllegalArgumentException("maxThreads must be > 0");
        }
        if (maxMemory <= 0) {
            throw new IllegalArgumentException("maxMemory must be > 0");
        }
    }
}
