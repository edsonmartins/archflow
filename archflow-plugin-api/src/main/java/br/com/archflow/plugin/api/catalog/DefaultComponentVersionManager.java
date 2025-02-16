package br.com.archflow.plugin.api.catalog;

import br.com.archflow.model.ai.metadata.ComponentMetadata;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementação do gerenciador de versões de componentes.
 */
public class DefaultComponentVersionManager implements ComponentVersionManager {
    private final Map<String, Map<String, ComponentMetadata>> versions = new ConcurrentHashMap<>();

    @Override
    public void registerVersion(String componentId, String version, ComponentMetadata metadata) {
        metadata.validate();
        versions.computeIfAbsent(componentId, k -> new ConcurrentHashMap<>())
               .put(version, metadata);
    }

    @Override
    public Optional<ComponentMetadata> getVersion(String componentId, String version) {
        return Optional.ofNullable(
            versions.getOrDefault(componentId, Map.of())
                   .get(version)
        );
    }

    @Override
    public List<String> getVersions(String componentId) {
        return versions.getOrDefault(componentId, Map.of())
                      .keySet()
                      .stream()
                      .sorted()
                      .toList();
    }

    @Override
    public Optional<ComponentMetadata> getLatestVersion(String componentId) {
        return versions.getOrDefault(componentId, Map.of())
                      .entrySet()
                      .stream()
                      .max(Map.Entry.comparingByKey())
                      .map(Map.Entry::getValue);
    }
}