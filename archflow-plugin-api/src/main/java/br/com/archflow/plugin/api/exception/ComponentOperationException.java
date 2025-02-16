package br.com.archflow.plugin.api.exception;

/**
 * Exceção lançada quando há erro na execução de uma operação.
 */
public class ComponentOperationException extends ComponentException {
    public ComponentOperationException(String message) {
        super(message);
    }

    public ComponentOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}