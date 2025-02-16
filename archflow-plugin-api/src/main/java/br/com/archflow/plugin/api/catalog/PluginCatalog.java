package br.com.archflow.plugin.api.catalog;

import br.com.archflow.plugin.api.model.PluginMetadata;

import java.util.*;

/**
 * Catálogo de plugins disponíveis no archflow.
 * Mantém registro de todos os plugins e suas metadata.
 */
public interface PluginCatalog {
    /**
     * Registra um novo plugin no catálogo.
     *
     * @param metadata metadata do plugin
     * @throws br.com.archflow.plugin.api.exception.PluginValidationException se houver erro de validação
     */
    void registerPlugin(PluginMetadata metadata);

    /**
     * Busca um plugin por ID.
     *
     * @param pluginId ID do plugin
     * @return metadata do plugin se encontrado
     */
    Optional<PluginMetadata> getPlugin(String pluginId);

    /**
     * Lista todos os plugins disponíveis.
     *
     * @return lista de plugins
     */
    List<PluginMetadata> listPlugins();

    /**
     * Busca plugins por critérios.
     *
     * @param criteria critérios de busca
     * @return plugins que atendem aos critérios
     */
    List<PluginMetadata> searchPlugins(PluginSearchCriteria criteria);
}