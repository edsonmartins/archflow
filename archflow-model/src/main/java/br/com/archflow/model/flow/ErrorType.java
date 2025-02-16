package br.com.archflow.model.flow;

/**
 * Tipos de erro que podem ocorrer durante a execução de um passo
 */
public enum ErrorType {
    /**
     * Erro de validação (ex: parâmetros inválidos)
     */
    VALIDATION,

    /**
     * Erro durante a execução
     */
    EXECUTION,

    /**
     * Timeout na execução
     */
    TIMEOUT,

    /**
     * Erro relacionado ao LLM
     */
    LLM,

    /**
     * Erro de sistema (ex: falta de recursos)
     */
    SYSTEM,

    /**
     * Erro de permissão
     */
    PERMISSION,

    /**
     * Erro de conexão
     */
    CONNECTION
}