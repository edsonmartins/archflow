package br.com.archflow.model.metrics;

import java.util.Map;

/**
 * Métricas específicas de um passo.
 */
public record StepMetrics(
    /** Tempo de execução do passo em ms */
    long executionTime,
    
    /** Tokens consumidos pelo passo */
    int tokensUsed,
    
    /** Número de retries */
    int retryCount,
    
    /** Métricas adicionais */
    Map<String, Object> additionalMetrics
) {}