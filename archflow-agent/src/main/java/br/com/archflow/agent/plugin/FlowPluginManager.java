package br.com.archflow.agent.plugin;

import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import br.com.archflow.plugin.api.catalog.DefaultComponentCatalog;

import dev.jeka.core.api.depmanagement.JkDependencySet;
import dev.jeka.core.api.depmanagement.JkRepo;
import dev.jeka.core.api.depmanagement.resolution.JkDependencyResolver;
import dev.jeka.core.api.depmanagement.resolution.JkResolveResult;
import dev.jeka.core.api.file.JkPathSequence;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Gerenciador de plugins do ArchFlow.
 * Responsável por baixar, carregar e gerenciar plugins necessários para execução dos fluxos.
 */
public class FlowPluginManager {
    private static final Logger logger = Logger.getLogger(FlowPluginManager.class.getName());

    private final String pluginsPath;
    private final ComponentCatalog catalog;
    private final Map<String, PluginInfo> loadedPlugins;
    private URLClassLoader pluginClassLoader;
    private final Set<URL> loadedUrls;

    public FlowPluginManager(String pluginsPath) {
        this.pluginsPath = pluginsPath;
        this.catalog = new DefaultComponentCatalog();
        this.loadedPlugins = new ConcurrentHashMap<>();
        this.loadedUrls = new LinkedHashSet<>();
    }

    /**
     * Carrega plugins necessários para um fluxo
     */
    public void loadPluginsForFlow(Flow flow) throws Exception {
//        Set<URL> urls = new LinkedHashSet<>();
//        logger.info("Iniciando carregamento de plugins para fluxo: " + flow.getId());
//
//        // Coleta plugins necessários para cada step
//        for (FlowStep step : flow.getSteps()) {
//            if (step.getType() == null) {
//                logger.warning("Step sem tipo definido: " + step.getId());
//                continue;
//            }
//
//            try {
//                // Obtém informações do plugin baseado no step
//                PluginInfo pluginInfo = getPluginInfo(step);
//                logger.info("Processando plugin: " + pluginInfo);
//
//                // Download do plugin e dependências
//                Set<URL> pluginUrls = downloadPluginAndDependencies(pluginInfo, urls);
//                urls.addAll(pluginUrls);
//
//                // Registra plugin carregado
//                loadedPlugins.put(pluginInfo.getId(), pluginInfo);
//
//            } catch (Exception e) {
//                logger.severe("Erro carregando plugin para step " + step.getId() + ": " + e.getMessage());
//                throw new ComponentLoadException(
//                        "Erro carregando plugin: " + e.getMessage(),
//                        step.getType(),
//                        step.getId()
//                );
//            }
//        }
//
//        // Cria novo ClassLoader com todas as URLs
//        this.pluginClassLoader = new URLClassLoader(
//                urls.toArray(new URL[0]),
//                getClass().getClassLoader()
//        );
//        this.loadedUrls.addAll(urls);
//
//        logger.info("Plugins carregados com sucesso. Total de URLs: " + urls.size());
    }

    /**
     * Download de plugin e suas dependências
     */
    private Set<URL> downloadPluginAndDependencies(PluginInfo plugin, Set<URL> globalUrls) throws Exception {
//        Set<URL> urls = new LinkedHashSet<>();
//
//        // 1. Download do plugin principal
//        String coordinates = String.format(
//                "br.com.archflow:archflow-plugin-%s:%s",
//                plugin.getId(),
//                plugin.getVersion()
//        );
//
//        logger.info("Resolvendo dependência: " + coordinates);
//
//        // Configura resolver do Jeka
//        JkDependencyResolver resolver = JkDependencyResolver.of(
//                JkRepo.ofMavenLocal(),
//                JkRepo.ofMavenCentral()
//        );
//
//        // Resolve plugin principal
//        JkDependencySet deps = JkDependencySet.of().and(coordinates);
//        JkResolveResult result = resolver.resolve(deps);
//        JkPathSequence files = result.getFiles();
//
//        // Adiciona arquivos resolvidos
//        for (Path path : files.getEntries()) {
//            URL url = path.toUri().toURL();
//            if (!globalUrls.contains(url)) {
//                urls.add(url);
//                logger.fine("URL adicionada: " + url);
//            }
//        }
//
//        // 2. Resolve dependências transitivas
//        String[] dependencies = plugin.getDependencies();
//        if (dependencies != null && dependencies.length > 0) {
//            List<String> toBeFetched = filterExistingDependencies(dependencies, globalUrls);
//            logger.info("Dependências a serem baixadas: " + toBeFetched);
//
//            if (!toBeFetched.isEmpty()) {
//                deps = JkDependencySet.of();
//                for (String dep : toBeFetched) {
//                    deps = deps.and(dep);
//                }
//
//                result = resolver.resolve(deps);
//                for (Path path : result.getFiles().getEntries()) {
//                    urls.add(path.toUri().toURL());
//                }
//            }
//        }
//
//        return urls;
        return null;
    }

    /**
     * Filtra dependências que já existem no classpath
     */
    private List<String> filterExistingDependencies(String[] dependencies, Set<URL> globalUrls) {
        String[] classpath = getSystemClassPath(globalUrls);
        return Arrays.stream(dependencies)
                .filter(dep -> {
                    String artifactId = dep.split(":")[1];
                    return Arrays.stream(classpath)
                            .noneMatch(cp -> cp.contains(artifactId));
                })
                .toList();
    }

    /**
     * Obtém classpath completo do sistema
     */
    private String[] getSystemClassPath(Set<URL> globalUrls) {
        List<String> entries = new ArrayList<>();

        // Classpath do sistema
        entries.addAll(Arrays.asList(
                System.getProperty("java.class.path").split(File.pathSeparator)
        ));

        // URLs globais
        entries.addAll(
                globalUrls.stream()
                        .map(URL::getPath)
                        .toList()
        );

        return entries.toArray(new String[0]);
    }

    /**
     * Extrai informações do plugin a partir do step
     */
    private PluginInfo getPluginInfo(FlowStep step) {
        String type = step.getType().name().toLowerCase();
        String version = getVersionFromMetadata(step);
        String[] dependencies = getDependenciesFromMetadata(step);

        return new PluginInfo(
                type,
                version,
                dependencies
        );
    }

    private String getVersionFromMetadata(FlowStep step) {
//        return step.getMetadata() != null && step.getMetadata().containsKey("version")
//                ? step.getMetadata().get("version").toString()
//                : "1.0.0"; // Versão default
        return "";
    }

    private String[] getDependenciesFromMetadata(FlowStep step) {
//        if (step.getMetadata() == null || !step.getMetadata().containsKey("dependencies")) {
//            return new String[0];
//        }
//        Object deps = step.getMetadata().get("dependencies");
//        if (deps instanceof String[]) {
//            return (String[]) deps;
//        }
//        if (deps instanceof List) {
//            List<?> list = (List<?>) deps;
//            return list.stream()
//                    .map(Object::toString)
//                    .toArray(String[]::new);
//        }
//        return new String[0];
        return null;
    }

    /**
     * Obtém ClassLoader com plugins carregados
     */
    public URLClassLoader getPluginClassLoader() {
        return pluginClassLoader;
    }

    /**
     * Limpa plugins carregados
     */
    public void clearPlugins() {
        if (pluginClassLoader != null) {
            try {
                pluginClassLoader.close();
            } catch (Exception e) {
                logger.warning("Erro fechando ClassLoader: " + e.getMessage());
            }
        }
        loadedPlugins.clear();
        loadedUrls.clear();
        pluginClassLoader = null;
    }

    /**
     * Informações de um plugin
     */
    private record PluginInfo(
            String id,
            String version,
            String[] dependencies
    ) {
        @Override
        public String toString() {
            return "Plugin[id=" + id + ", version=" + version + "]";
        }
    }
}