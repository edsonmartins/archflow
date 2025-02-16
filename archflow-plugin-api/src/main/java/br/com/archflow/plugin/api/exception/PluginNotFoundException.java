package br.com.archflow.plugin.api.exception;

/**
 * Exceção lançada quando um plugin não é encontrado.
 */
public class PluginNotFoundException extends PluginException {
    private final String pluginId;

    public PluginNotFoundException(String pluginId) {
        super("Plugin not found: " + pluginId);
        this.pluginId = pluginId;
    }

    public String getPluginId() {
        return pluginId;
    }
}