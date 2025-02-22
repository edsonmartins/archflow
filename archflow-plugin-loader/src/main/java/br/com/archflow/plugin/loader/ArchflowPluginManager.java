package br.com.archflow.plugin.loader;

import br.com.archflow.model.ai.AIAgent;
import br.com.archflow.model.ai.AIAssistant;
import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.ai.Tool;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import br.com.archflow.plugin.api.catalog.ComponentSearchCriteria;
import br.com.archflow.plugin.api.catalog.DefaultComponentCatalog;
import br.com.archflow.plugin.api.spi.ComponentPlugin;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class ArchflowPluginManager {
    private final Map<String, ComponentPlugin> loadedPlugins = new ConcurrentHashMap<>();
    private final ComponentCatalog catalog = new DefaultComponentCatalog();

    private void installPlugin(ComponentPlugin plugin) {
        // Valida que o plugin implementa uma das interfaces de IA
        if (!(plugin instanceof AIComponent)) {
            throw new PluginLoadException("Plugin must implement an AI component interface");
        }

        AIComponent component = (AIComponent) plugin;
        ComponentMetadata metadata = component.getMetadata();
        metadata.validate();

        // Registra no catálogo como AIComponent
        catalog.register(component);

        // Mantém referência como plugin
        loadedPlugins.put(metadata.id(), plugin);

        try {
            plugin.onLoad(null);
        } catch (Exception e) {
            catalog.unregister(metadata.id());
            loadedPlugins.remove(metadata.id());
            throw new PluginLoadException("Error initializing plugin: " + metadata.id(), e);
        }
    }

    public <T extends AIComponent> List<T> getComponentsByType(ComponentType type) {
        return catalog.searchComponents(
                        ComponentSearchCriteria.builder()
                                .type(type)
                                .build()
                )
                .stream()
                .map(meta -> {
                    ComponentPlugin plugin = loadedPlugins.get(meta.id());
                    if (plugin instanceof AIComponent) {
                        return (T) plugin;
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    // Helper methods remain the same but now work correctly since we validate
    // plugin implements correct interface
    public List<AIAssistant> getAssistants() {
        return getComponentsByType(ComponentType.ASSISTANT);
    }

    public List<AIAgent> getAgents() {
        return getComponentsByType(ComponentType.AGENT);
    }

    public List<Tool> getTools() {
        return getComponentsByType(ComponentType.TOOL);
    }
}