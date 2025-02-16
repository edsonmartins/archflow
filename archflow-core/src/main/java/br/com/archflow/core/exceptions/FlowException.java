package br.com.archflow.core.exceptions;

/**
 * Exceção base para erros relacionados a fluxos.
 */
public class FlowException extends RuntimeException {
    public FlowException(String message) {
        super(message);
    }

    public FlowException(String message, Throwable cause) {
        super(message, cause);
    }
}