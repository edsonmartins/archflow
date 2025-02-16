package br.com.archflow.plugin.loader;

import br.com.archflow.plugin.api.metadata.PluginDescriptor;
import br.com.archflow.plugin.api.spi.AIPlugin;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Gerenciador de plugins do archflow.
 * Responsável por carregar, instalar e gerenciar plugins.
 */
public class ArchflowPluginManager {
    
    private final Map<String, AIPlugin> loadedPlugins = new ConcurrentHashMap<>();
    private final File pluginsDirectory;
    private final AtomicBoolean loading = new AtomicBoolean(false);

    public ArchflowPluginManager(File pluginsDirectory) {
        this.pluginsDirectory = pluginsDirectory;
    }

    /**
     * Carrega todos os plugins do diretório configurado.
     */
    public void loadPlugins() {
        if (!pluginsDirectory.exists() || !pluginsDirectory.isDirectory()) {
            throw new PluginLoadException(
                "Diretório de plugins não encontrado: " + pluginsDirectory
            );
        }

        if (loading.compareAndSet(false, true)) {
            try {
                File[] directories = pluginsDirectory.listFiles(File::isDirectory);
                if (directories != null) {
                    for (File pluginDir : directories) {
                        loadPlugin(pluginDir);
                    }
                }
            } finally {
                loading.set(false);
            }
        }
    }

    /**
     * Carrega um plugin específico.
     */
    private void loadPlugin(File pluginDir) {
        try {
            URLClassLoader pluginClassLoader = createPluginClassLoader(pluginDir);
            ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
            
            try {
                Thread.currentThread().setContextClassLoader(pluginClassLoader);
                
                // Usa ServiceLoader para carregar as implementações de AIPlugin
                ServiceLoader<AIPlugin> serviceLoader = ServiceLoader.load(
                    AIPlugin.class, 
                    pluginClassLoader
                );
                
                for (AIPlugin plugin : serviceLoader) {
                    installPlugin(plugin);
                }
                
            } finally {
                Thread.currentThread().setContextClassLoader(contextClassLoader);
            }
            
        } catch (Exception e) {
            throw new PluginLoadException(
                "Erro carregando plugin do diretório: " + pluginDir, 
                e
            );
        }
    }

    /**
     * Instala um plugin no sistema.
     */
    private void installPlugin(AIPlugin plugin) {
        String pluginId = plugin.getClass().getAnnotation(PluginDescriptor.class).id();
        loadedPlugins.put(pluginId, plugin);
    }

    /**
     * Cria o ClassLoader para um plugin.
     */
    private URLClassLoader createPluginClassLoader(File pluginDir) {
        try {
            List<URL> urls = new ArrayList<>();
            
            // Adiciona JARs do diretório do plugin
            File[] files = pluginDir.listFiles((dir, name) -> 
                name.toLowerCase().endsWith(".jar")
            );
            
            if (files != null) {
                for (File file : files) {
                    urls.add(file.toURI().toURL());
                }
            }

            return new ArchflowPluginClassLoader(
                urls.toArray(new URL[0]),
                getClass().getClassLoader()
            );
            
        } catch (Exception e) {
            throw new PluginLoadException(
                "Erro criando ClassLoader para plugin: " + pluginDir,
                e
            );
        }
    }

    /**
     * Obtém um plugin pelo ID.
     */
    public Optional<AIPlugin> getPlugin(String pluginId) {
        return Optional.ofNullable(loadedPlugins.get(pluginId));
    }

    /**
     * Lista todos os plugins carregados.
     */
    public Collection<AIPlugin> getLoadedPlugins() {
        return Collections.unmodifiableCollection(loadedPlugins.values());
    }
}