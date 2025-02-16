package br.com.archflow.core;

import java.util.Map;

/**
 * Configurações de monitoramento.
 */
public record MonitoringConfig(
    /** Se deve coletar métricas detalhadas */
    boolean detailedMetrics,
    
    /** Se deve manter histórico completo */
    boolean fullHistory,
    
    /** Nível de log desejado */
    LogLevel logLevel,
    
    /** Tags para métricas */
    Map<String, String> tags
) {}