package br.com.archflow.plugin.api.exception;

/**
 * Exceção base para erros relacionados a componentes.
 */
public class ComponentException extends RuntimeException {
    public ComponentException(String message) {
        super(message);
    }

    public ComponentException(String message, Throwable cause) {
        super(message, cause);
    }
}