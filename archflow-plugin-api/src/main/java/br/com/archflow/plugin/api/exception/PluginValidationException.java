package br.com.archflow.plugin.api.exception;

/**
 * Exceção lançada quando há erro na validação de um plugin.
 */
public class PluginValidationException extends PluginException {
    
    public PluginValidationException(String message) {
        super(message);
    }

    public PluginValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}