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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class ArchflowPluginManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ArchflowPluginManager.class);

    private final Map<String, ComponentPlugin> loadedPlugins = new ConcurrentHashMap<>();
    /** Tracks the classloader that produced each plugin so {@link #unload(String)}
     *  can close it and release jar file handles. Plugins loaded from the
     *  application classpath have no entry here. */
    private final Map<String, ArchflowPluginClassLoader> classLoaders = new ConcurrentHashMap<>();
    private final ComponentCatalog catalog = new DefaultComponentCatalog();

    private void installPlugin(ComponentPlugin plugin) {
        installPlugin(plugin, null);
    }

    private void installPlugin(ComponentPlugin plugin, ArchflowPluginClassLoader classLoader) {
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
        if (classLoader != null) {
            classLoaders.put(metadata.id(), classLoader);
        }

        try {
            plugin.onLoad(null);
        } catch (Exception e) {
            catalog.unregister(metadata.id());
            loadedPlugins.remove(metadata.id());
            classLoaders.remove(metadata.id());
            throw new PluginLoadException("Error initializing plugin: " + metadata.id(), e);
        }
    }

    /**
     * Unloads a single plugin: invokes its lifecycle hook, unregisters it
     * from the catalog, and (if it was loaded via a dedicated classloader)
     * closes that classloader. Without this last step, redeploying or
     * hot-reloading plugins leaks {@code URLClassLoader} instances and
     * keeps jar file handles open until JVM exit.
     *
     * @param pluginId metadata id of the plugin to unload
     */
    public void unload(String pluginId) {
        ComponentPlugin plugin = loadedPlugins.remove(pluginId);
        if (plugin != null) {
            try {
                plugin.onUnload();
            } catch (Exception e) {
                log.warn("Plugin {} threw during onUnload: {}", pluginId, e.getMessage());
            }
            catalog.unregister(pluginId);
        }
        ArchflowPluginClassLoader cl = classLoaders.remove(pluginId);
        if (cl != null) {
            try {
                cl.close();
            } catch (IOException e) {
                log.warn("Failed to close classloader for plugin {}: {}", pluginId, e.getMessage());
            }
        }
    }

    /** Closes all plugin classloaders. Call on shutdown. */
    @Override
    public void close() {
        for (String id : List.copyOf(loadedPlugins.keySet())) {
            unload(id);
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