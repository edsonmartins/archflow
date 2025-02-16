package br.com.archflow.model.error;

/**
 * Tipos de erros de execução
 */
public enum ExecutionErrorType {
    /**
     * Erro de configuração
     */
    CONFIGURATION,

    /**
     * Erro de validação
     */
    VALIDATION,

    /**
     * Erro de execução
     */
    EXECUTION,

    /**
     * Erro de sistema
     */
    SYSTEM,

    /**
     * Erro de conexão
     */
    CONNECTION,

    /**
     * Erro de autorização
     */
    AUTHORIZATION,

    /**
     * Timeout
     */
    TIMEOUT,

    /**
     * Erro de recurso não encontrado
     */
    NOT_FOUND,

    /**
     * Erro de estado inválido
     */
    INVALID_STATE,

    /**
     * Erro desconhecido
     */
    UNKNOWN
}