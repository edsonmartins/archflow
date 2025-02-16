package br.com.archflow.plugin.api.catalog;

import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import java.util.List;
import java.util.Optional;

/**
 * Catálogo de componentes de IA disponíveis.
 */
public interface ComponentCatalog {
    /**
     * Registra um novo componente.
     */
    void register(AIComponent component);

    /**
     * Remove um componente do catálogo.
     */
    void unregister(String componentId);

    /**
     * Busca um componente por ID.
     */
    Optional<AIComponent> getComponent(String componentId);

    /**
     * Retorna os metadados de um componente.
     */
    Optional<ComponentMetadata> getMetadata(String componentId);

    /**
     * Lista todos os componentes disponíveis.
     */
    List<ComponentMetadata> listComponents();

    /**
     * Busca componentes por critérios.
     */
    List<ComponentMetadata> searchComponents(ComponentSearchCriteria criteria);

    /**
     * Retorna o gerenciador de versões.
     */
    ComponentVersionManager getVersionManager();

    /**
     * Registra uma nova versão de um componente.
     */
    default void registerVersion(String componentId, String version, AIComponent component) {
        component.getMetadata().validate();
        getVersionManager().registerVersion(componentId, version, component.getMetadata());
    }
}