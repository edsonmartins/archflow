package br.com.archflow.agent.config;

import br.com.archflow.model.enums.LogLevel;

import java.util.Map;

/**
 * Configuração de monitoramento
 */
public record MonitoringConfig(
    boolean metricsEnabled,
    LogLevel logLevel,
    int metricsInterval,
    Map<String, String> labels
) {
    public MonitoringConfig {
        if (metricsInterval <= 0) {
            throw new IllegalArgumentException("metricsInterval must be > 0");
        }
    }
}