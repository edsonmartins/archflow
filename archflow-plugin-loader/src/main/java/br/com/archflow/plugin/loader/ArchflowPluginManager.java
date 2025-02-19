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

    /**
     * Instala um plugin no sistema.
     */
    private void installPlugin(ComponentPlugin plugin) {
        // Validar tipo do componente
        ComponentMetadata metadata = plugin.getMetadata();
        metadata.validate();
        
        // Registrar no catálogo
        catalog.register(plugin);
        
        // Manter referência local
        loadedPlugins.put(metadata.id(), plugin);
        
        // Inicializar plugin
        try {
            plugin.onLoad(null); // TODO: Passar contexto adequado
        } catch (Exception e) {
            catalog.unregister(metadata.id());
            loadedPlugins.remove(metadata.id());
            throw new PluginLoadException("Erro inicializando plugin: " + metadata.id(), e);
        }
    }

    /**
     * Obtém componentes por tipo.
     */
    public <T extends AIComponent> List<T> getComponentsByType(ComponentType type) {
        return catalog.searchComponents(
            ComponentSearchCriteria.builder()
                .type(type)
                .build()
        )
        .stream()
        .map(meta -> (T)loadedPlugins.get(meta.id()))
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
    }

    // Helper methods
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