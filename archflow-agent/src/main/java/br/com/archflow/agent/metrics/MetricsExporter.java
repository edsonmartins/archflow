package br.com.archflow.agent.metrics;

import br.com.archflow.agent.config.AgentConfig;

/**
 * Exportador de métricas que envia dados para sistemas externos
 */
class MetricsExporter {
    private final AgentConfig config;

    public MetricsExporter(AgentConfig config) {
        this.config = config;
    }

    public void export(AggregatedMetrics metrics) {
        // TODO: Implementar exportação para sistemas externos
        // Por exemplo: Prometheus, InfluxDB, etc.
    }
}

