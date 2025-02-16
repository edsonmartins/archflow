package br.com.archflow.plugin.api.catalog;

import br.com.archflow.plugin.api.model.PluginMetadata;

import java.util.List;
import java.util.Optional;

/**
 * Interface para gerenciar versões de plugins.
 */
public interface PluginVersionManager {
    /**
     * Registra uma nova versão de plugin.
     *
     * @param pluginId ID do plugin
     * @param version versão
     * @param metadata metadata da versão
     */
    void registerVersion(String pluginId, String version, PluginMetadata metadata);

    /**
     * Lista todas as versões de um plugin.
     *
     * @param pluginId ID do plugin
     * @return versões disponíveis
     */
    List<String> listVersions(String pluginId);

    /**
     * Obtém metadata de uma versão específica.
     *
     * @param pluginId ID do plugin
     * @param version versão desejada
     * @return metadata da versão
     */
    Optional<PluginMetadata> getVersion(String pluginId, String version);
}