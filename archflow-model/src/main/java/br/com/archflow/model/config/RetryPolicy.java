package br.com.archflow.core;

import java.util.Set;

/**
 * Política de retry para execuções com erro.
 */
public record RetryPolicy(
    /** Número máximo de tentativas */
    int maxAttempts,
    
    /** Delay entre tentativas em ms */
    long delay,
    
    /** Fator de multiplicação do delay */
    double multiplier,
    
    /** Tipos de erros que podem ser retentados */
    Set<Class<? extends Throwable>> retryableExceptions
) {}