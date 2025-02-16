package br.com.archflow.model.error;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Representa um erro ocorrido durante a execução de um fluxo.
 * Mais abrangente que o StepError, pois pode representar erros
 * que não estão associados a um passo específico.
 */
public record ExecutionError(
    /**
     * Código do erro para identificação
     */
    String code,

    /**
     * Mensagem descritiva do erro
     */
    String message,

    /**
     * Tipo do erro
     */
    ExecutionErrorType type,

    /**
     * Componente onde ocorreu o erro (se aplicável)
     */
    String component,

    /**
     * Momento em que o erro ocorreu
     */
    Instant timestamp,

    /**
     * Causa original do erro (opcional)
     */
    Throwable cause,

    /**
     * Dados adicionais do erro
     */
    Map<String, Object> details
) {
    /**
     * Cria um novo ExecutionError com timestamp atual
     */
    public static ExecutionError of(String code, String message, ExecutionErrorType type) {
        return new ExecutionError(
            code,
            message,
            type,
            null,
            Instant.now(),
            null,
            new HashMap<>()
        );
    }

    /**
     * Cria um novo ExecutionError com componente
     */
    public static ExecutionError of(String code, String message, ExecutionErrorType type, String component) {
        return new ExecutionError(
            code,
            message,
            type,
            component,
            Instant.now(),
            null,
            new HashMap<>()
        );
    }

    /**
     * Cria um novo ExecutionError a partir de uma exceção
     */
    public static ExecutionError fromException(String code, Throwable cause, String component) {
        return new ExecutionError(
            code,
            cause.getMessage(),
            ExecutionErrorType.SYSTEM,
            component,
            Instant.now(),
            cause,
            new HashMap<>()
        );
    }

    /**
     * Adiciona detalhes ao erro
     */
    public ExecutionError withDetail(String key, Object value) {
        Map<String, Object> newDetails = new HashMap<>(this.details);
        newDetails.put(key, value);
        return new ExecutionError(
            this.code,
            this.message,
            this.type,
            this.component,
            this.timestamp,
            this.cause,
            newDetails
        );
    }
}