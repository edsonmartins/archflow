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
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Loads, catalogs and unloads {@link ComponentPlugin}s discovered via
 * {@link ServiceLoader} ({@code META-INF/services}).
 *
 * <h2>Modelo de empacotamento: FAT-JARS obrigatórios</h2>
 * <p><strong>Não há resolução de dependências em runtime.</strong> Cada jar de
 * plugin colocado no diretório de plugins deve ser um <em>fat-jar</em>
 * (uber-jar) contendo todas as suas dependências, exceto as classes
 * compartilhadas com a aplicação (modelo, plugin-api, LangChain4j — ver
 * {@link ArchflowPluginClassLoader}). Versões antigas da documentação
 * prometiam resolução dinâmica de dependências via Jeka; isso nunca foi
 * implementado e a promessa foi removida — se uma dependência não estiver
 * dentro do jar nem no classpath da aplicação, o plugin falha com
 * {@code ClassNotFoundException}.
 *
 * <h2>Isolamento: child-first com fallback total ao pai</h2>
 * <p>O isolamento de classloader é <em>child-first</em> com fallback completo
 * ao classloader pai: classes não encontradas nos jars do plugin são
 * delegadas ao classloader da aplicação. Isso significa que <strong>plugins
 * enxergam todas as classes da aplicação hospedeira</strong> — o isolamento
 * evita conflitos de versão na direção plugin→aplicação, mas não é uma
 * barreira de visibilidade nem de segurança.
 *
 * <h2>Fronteira de confiança: sem sandbox</h2>
 * <p>{@link ComponentPlugin#onLoad} (e qualquer inicializador estático das
 * classes do jar) executa <strong>código arbitrário do jar, sem sandbox,
 * sem SecurityManager e com os mesmos privilégios da JVM hospedeira</strong>.
 * Carregar um jar de plugin equivale a executar aquele código com acesso
 * total ao processo (filesystem, rede, variáveis de ambiente, secrets).
 * <em>Só carregue jars de fontes confiáveis.</em> O diretório de plugins é a
 * fronteira de confiança do sistema: proteja-o com permissões de filesystem
 * e controle de quem pode publicar jars nele.
 */
public class ArchflowPluginManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ArchflowPluginManager.class);

    private final Map<String, ComponentPlugin> loadedPlugins = new ConcurrentHashMap<>();
    /** Tracks the classloader that produced each plugin so {@link #unload(String)}
     *  can close it and release jar file handles. Plugins loaded from the
     *  application classpath have no entry here. */
    private final Map<String, ArchflowPluginClassLoader> classLoaders = new ConcurrentHashMap<>();
    private final ComponentCatalog catalog = new DefaultComponentCatalog();

    /**
     * Discovers and installs every {@link ComponentPlugin} registered (via
     * {@code META-INF/services}) and visible to the given classloader.
     *
     * @return metadata ids of the plugins installed by this call
     * @throws PluginLoadException if any discovered plugin fails to install —
     *         loading is all-or-nothing per call so a broken plugin never
     *         disappears silently
     */
    public List<String> loadFromClassLoader(ClassLoader classLoader) {
        List<String> installed = new ArrayList<>();
        try {
            for (ComponentPlugin plugin : ServiceLoader.load(ComponentPlugin.class, classLoader)) {
                installPlugin(plugin,
                        classLoader instanceof ArchflowPluginClassLoader acl ? acl : null);
                installed.add(((AIComponent) plugin).getMetadata().id());
            }
        } catch (PluginLoadException e) {
            throw e;
        } catch (Throwable e) {
            // ServiceConfigurationError etc. — a broken registration must fail loudly
            throw new PluginLoadException("Plugin discovery failed: " + e.getMessage(), e);
        }
        if (!installed.isEmpty()) {
            log.info("Loaded {} plugin(s): {}", installed.size(), installed);
        }
        return installed;
    }

    /**
     * Loads every plugin found in the {@code *.jar} files of a directory.
     * All jars share one {@link ArchflowPluginClassLoader} (child-first, com
     * fallback total ao classloader pai — ver javadoc da classe).
     *
     * <p>Os jars devem ser <strong>fat-jars confiáveis</strong>: não há
     * resolução de dependências e {@code onLoad} executa código do jar sem
     * sandbox (ver javadoc da classe sobre a fronteira de confiança).
     *
     * <p>A missing or empty directory is not an error — it simply loads
     * nothing (and logs at INFO). A jar that fails to load IS an error.
     *
     * @return metadata ids of the plugins installed by this call
     */
    public List<String> loadFromDirectory(Path pluginsDir) {
        if (pluginsDir == null || !Files.isDirectory(pluginsDir)) {
            log.info("Plugins directory {} does not exist — no external plugins loaded", pluginsDir);
            return List.of();
        }

        List<URL> jarUrls = new ArrayList<>();
        try (Stream<Path> entries = Files.list(pluginsDir)) {
            entries.filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .forEach(p -> {
                        try {
                            jarUrls.add(p.toUri().toURL());
                        } catch (IOException e) {
                            throw new PluginLoadException("Invalid plugin jar path: " + p, e);
                        }
                    });
        } catch (IOException e) {
            throw new PluginLoadException("Cannot list plugins directory: " + pluginsDir, e);
        }

        if (jarUrls.isEmpty()) {
            log.info("No plugin jars found in {}", pluginsDir);
            return List.of();
        }

        ArchflowPluginClassLoader loader = new ArchflowPluginClassLoader(
                jarUrls.toArray(new URL[0]), getClass().getClassLoader());
        try {
            return loadFromClassLoader(loader);
        } catch (RuntimeException e) {
            try {
                loader.close();
            } catch (IOException closeError) {
                log.warn("Failed to close plugin classloader after load error", closeError);
            }
            throw e;
        }
    }

    /** Returns the plugin instance registered under the given metadata id. */
    public Optional<ComponentPlugin> getPlugin(String pluginId) {
        return Optional.ofNullable(loadedPlugins.get(pluginId));
    }

    /** Metadata ids of every plugin currently loaded. */
    public Set<String> getLoadedPluginIds() {
        return Set.copyOf(loadedPlugins.keySet());
    }

    /** The catalog all loaded plugins are registered in. */
    public ComponentCatalog getCatalog() {
        return catalog;
    }

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
        // Vários plugins de um mesmo diretório compartilham um classloader —
        // só fecha quando este era o último plugin que o referenciava.
        if (cl != null && !classLoaders.containsValue(cl)) {
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