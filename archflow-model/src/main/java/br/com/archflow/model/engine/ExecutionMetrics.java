package br.com.archflow.model.engine;

import br.com.archflow.model.metrics.StepMetrics;

import java.util.Map;

/**
 * Métricas coletadas durante execução.
 */
public record ExecutionMetrics(
    /** Tempo total de execução em ms */
    long executionTime,
    
    /** Número de tokens consumidos */
    int tokensUsed,
    
    /** Custo estimado da execução */
    double estimatedCost,
    
    /** Métricas específicas de passos */
    Map<String, StepMetrics> stepMetrics
) {}