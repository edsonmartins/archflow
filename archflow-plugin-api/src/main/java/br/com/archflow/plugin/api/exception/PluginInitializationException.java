package br.com.archflow.plugin.api.exception;

/**
 * Exceção lançada quando há erro na inicialização de um plugin.
 */
public class PluginInitializationException extends PluginException {
    
    public PluginInitializationException(String message) {
        super(message);
    }

    public PluginInitializationException(String message, Throwable cause) {
        super(message, cause);
    }
}