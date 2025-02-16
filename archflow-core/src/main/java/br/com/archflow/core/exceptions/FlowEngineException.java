package br.com.archflow.core.exceptions;

/**
 * Exceção lançada quando ocorre erro no engine de execução.
 */
public class FlowEngineException extends FlowException {
    public FlowEngineException(String message) {
        super(message);
    }

    public FlowEngineException(String message, Throwable cause) {
        super(message, cause);
    }
}