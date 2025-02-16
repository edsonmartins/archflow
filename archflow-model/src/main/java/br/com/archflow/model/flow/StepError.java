package br.com.archflow.model.flow;

import java.time.Instant;
import java.util.Map;

/**
 * Representa um erro ocorrido durante a execução de um passo no fluxo.
 */
public record StepError(
    /**
     * Tipo do erro
     */
    ErrorType type,

    /**
     * Código do erro para identificação
     */
    String code,

    /**
     * Mensagem descritiva do erro
     */
    String message,

    /**
     * Momento em que o erro ocorreu
     */
    Instant timestamp,

    /**
     * Contexto adicional do erro
     */
    Map<String, Object> context,

    /**
     * Causa original do erro (opcional)
     */
    Throwable cause
) {
    /**
     * Cria um novo StepError com timestamp atual
     */
    public static StepError of(ErrorType type, String code, String message) {
        return new StepError(
            type,
            code,
            message,
            Instant.now(),
            Map.of(),
            null
        );
    }

    /**
     * Cria um novo StepError com contexto
     */
    public static StepError of(ErrorType type, String code, String message, Map<String, Object> context) {
        return new StepError(
            type,
            code,
            message,
            Instant.now(),
            context,
            null
        );
    }

    /**
     * Cria um novo StepError a partir de uma exceção
     */
    public static StepError fromException(Throwable e, String code) {
        return new StepError(
            ErrorType.EXECUTION,
            code,
            e.getMessage(),
            Instant.now(),
            Map.of(),
            e
        );
    }
}