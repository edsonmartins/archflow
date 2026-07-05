package br.com.archflow.agent.plugin;

import br.com.archflow.model.flow.Flow;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import br.com.archflow.plugin.loader.ArchflowPluginManager;
import br.com.archflow.plugin.loader.PluginLoadException;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Gerenciador de plugins do agente: carrega os jars do diretório de plugins
 * configurado (uma única vez, de forma lazy) e expõe o classloader e o
 * catálogo resultantes para a execução de fluxos.
 *
 * <p>Reescrito sobre o {@link ArchflowPluginManager} do archflow-plugin-loader
 * (descoberta via {@code META-INF/services}, isolamento de classloader,
 * lifecycle onLoad/onUnload). A versão anterior tinha o corpo inteiro
 * comentado e era um no-op silencioso — fluxos seguiam sem os plugins e sem
 * nenhum erro.
 *
 * <p>Falhas de carga agora propagam como {@link PluginLoadException}: um
 * plugin quebrado interrompe a preparação do fluxo em vez de desaparecer.
 */
public class FlowPluginManager implements AutoCloseable {

    private static final Logger logger = Logger.getLogger(FlowPluginManager.class.getName());

    private final String pluginsPath;
    private final ArchflowPluginManager pluginManager;

    private volatile boolean initialized;
    private volatile ClassLoader pluginClassLoader;

    public FlowPluginManager(String pluginsPath) {
        this.pluginsPath = pluginsPath;
        this.pluginManager = new ArchflowPluginManager();
        // Sem plugins carregados, a execução usa o classloader da aplicação.
        this.pluginClassLoader = getClass().getClassLoader();
    }

    /**
     * Garante que os plugins do diretório configurado estão carregados antes
     * da execução do fluxo. O carregamento acontece uma única vez; chamadas
     * subsequentes são baratas.
     *
     * @throws PluginLoadException se algum plugin do diretório falhar ao carregar
     */
    public void loadPluginsForFlow(Flow flow) {
        ensureInitialized();
    }

    private synchronized void ensureInitialized() {
        if (initialized) {
            return;
        }
        if (pluginsPath != null && !pluginsPath.isBlank()) {
            List<String> loaded = pluginManager.loadFromDirectory(Path.of(pluginsPath));
            if (!loaded.isEmpty()) {
                // Plugins externos carregados: passa a usar o classloader deles
                // (delegação parent-first para os pacotes compartilhados da API).
                pluginManager.getPlugin(loaded.get(0))
                        .ifPresent(p -> pluginClassLoader = p.getClass().getClassLoader());
                logger.info("Plugins carregados de " + pluginsPath + ": " + loaded);
            }
        }
        initialized = true;
    }

    /**
     * Classloader para execução de fluxos: o dos plugins externos quando
     * existem, senão o da aplicação. Nunca {@code null} (a versão anterior
     * retornava null até o primeiro — e inexistente — carregamento).
     */
    public ClassLoader getPluginClassLoader() {
        return pluginClassLoader;
    }

    /** Catálogo com os componentes de todos os plugins carregados. */
    public ComponentCatalog getCatalog() {
        return pluginManager.getCatalog();
    }

    /** Ids dos plugins atualmente carregados. */
    public Set<String> getLoadedPluginIds() {
        return pluginManager.getLoadedPluginIds();
    }

    /**
     * Descarrega todos os plugins (lifecycle onUnload + fechamento de
     * classloaders) e volta ao classloader da aplicação.
     */
    public void clearPlugins() {
        pluginManager.close();
        pluginClassLoader = getClass().getClassLoader();
        initialized = false;
    }

    @Override
    public void close() {
        clearPlugins();
    }
}
