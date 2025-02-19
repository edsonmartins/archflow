package br.com.archflow.model.enums;

/**
 * Status possíveis de uma execução.
 */
public enum ExecutionStatus {
    /** Execução completada com sucesso */
    COMPLETED,
    
    /** Execução falhou */
    FAILED,
    
    /** Execução cancelada */
    CANCELLED,
    
    /** Execução em andamento */
    RUNNING,
    
    /** Execução pausada */
    PAUSED
}
