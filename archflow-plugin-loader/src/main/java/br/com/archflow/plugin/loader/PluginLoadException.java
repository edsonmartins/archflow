package br.com.archflow.plugin.loader;

/**
 * Exceção lançada durante o carregamento de plugins.
 */
class PluginLoadException extends RuntimeException {
    public PluginLoadException(String message) {
        super(message);
    }

    public PluginLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}