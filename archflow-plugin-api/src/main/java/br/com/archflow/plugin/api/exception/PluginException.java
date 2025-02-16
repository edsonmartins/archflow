package br.com.archflow.plugin.api.exception;

/**
 * Exceção base para erros relacionados a plugins.
 */
public class PluginException extends RuntimeException {
    
    public PluginException(String message) {
        super(message);
    }

    public PluginException(String message, Throwable cause) {
        super(message, cause);
    }
}