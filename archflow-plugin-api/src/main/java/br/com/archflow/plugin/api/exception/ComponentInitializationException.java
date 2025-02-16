package br.com.archflow.plugin.api.exception;

/**
 * Exceção lançada quando há erro na inicialização do componente.
 */
public class ComponentInitializationException extends ComponentException {
    public ComponentInitializationException(String message) {
        super(message);
    }

    public ComponentInitializationException(String message, Throwable cause) {
        super(message, cause);
    }
}