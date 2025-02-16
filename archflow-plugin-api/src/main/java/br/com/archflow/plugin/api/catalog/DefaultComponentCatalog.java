package br.com.archflow.plugin.api.catalog;

import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.ai.metadata.ComponentMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Implementação básica do catálogo de componentes.
 */
public class DefaultComponentCatalog implements ComponentCatalog {
    private final Map<String, AIComponent> components = new ConcurrentHashMap<>();
    private final Map<String, ComponentMetadata> metadata = new ConcurrentHashMap<>();
    private final ComponentVersionManager versionManager = new DefaultComponentVersionManager();

    @Override
    public ComponentVersionManager getVersionManager() {
        return versionManager;
    }

    @Override
    public void register(AIComponent component) {
        ComponentMetadata meta = component.getMetadata();
        meta.validate();

        String id = meta.id();
        components.put(id, component);
        metadata.put(id, meta);

        // Registra também como uma versão
        versionManager.registerVersion(id, meta.version(), meta);
    }

    @Override
    public void unregister(String componentId) {
        components.remove(componentId);
        metadata.remove(componentId);
    }

    @Override
    public Optional<AIComponent> getComponent(String componentId) {
        return Optional.ofNullable(components.get(componentId));
    }

    @Override
    public Optional<ComponentMetadata> getMetadata(String componentId) {
        return Optional.ofNullable(metadata.get(componentId));
    }

    @Override
    public List<ComponentMetadata> listComponents() {
        return new ArrayList<>(metadata.values());
    }

    @Override
    public List<ComponentMetadata> searchComponents(ComponentSearchCriteria criteria) {
        return metadata.values().stream()
            .filter(meta -> matchesCriteria(meta, criteria))
            .collect(Collectors.toList());
    }

    private boolean matchesCriteria(ComponentMetadata meta, ComponentSearchCriteria criteria) {
        // Filtra por tipo se especificado
        if (criteria.type() != null && meta.type() != criteria.type()) {
            return false;
        }

        // Filtra por capacidades se especificadas
        if (!criteria.capabilities().isEmpty() && 
            !meta.capabilities().containsAll(criteria.capabilities())) {
            return false;
        }

        // Filtra por texto se especificado
        if (criteria.textSearch() != null && !criteria.textSearch().isBlank()) {
            String search = criteria.textSearch().toLowerCase();
            return meta.name().toLowerCase().contains(search) ||
                   meta.description().toLowerCase().contains(search);
        }

        return true;
    }
}