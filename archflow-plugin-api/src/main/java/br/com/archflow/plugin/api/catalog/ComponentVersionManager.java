package br.com.archflow.plugin.api.catalog;

import br.com.archflow.model.ai.metadata.ComponentMetadata;
import java.util.List;
import java.util.Optional;

/**
 * Interface para gerenciamento de versões de componentes.
 */
public interface ComponentVersionManager {
    /**
     * Registra uma nova versão de um componente.
     */
    void registerVersion(String componentId, String version, ComponentMetadata metadata);

    /**
     * Obtém uma versão específica de um componente.
     */
    Optional<ComponentMetadata> getVersion(String componentId, String version);

    /**
     * Lista todas as versões disponíveis de um componente.
     */
    List<String> getVersions(String componentId);

    /**
     * Obtém a última versão de um componente.
     */
    Optional<ComponentMetadata> getLatestVersion(String componentId);
}