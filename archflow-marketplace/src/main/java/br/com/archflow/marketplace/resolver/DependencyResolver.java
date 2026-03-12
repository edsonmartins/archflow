package br.com.archflow.marketplace.resolver;

import br.com.archflow.marketplace.manifest.ExtensionManifest;
import br.com.archflow.marketplace.registry.ExtensionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Resolves extension dependencies with topological sorting, circular dependency
 * detection, and transitive dependency resolution.
 *
 * <p>Extracted and improved from {@code ExtensionInstaller} to provide standalone
 * dependency resolution capabilities.</p>
 */
public class DependencyResolver {

    private static final Logger log = LoggerFactory.getLogger(DependencyResolver.class);

    private final ExtensionRegistry registry;

    public DependencyResolver() {
        this.registry = ExtensionRegistry.getInstance();
    }

    public DependencyResolver(ExtensionRegistry registry) {
        this.registry = registry;
    }

    /**
     * Resolves all dependencies for a single extension manifest.
     *
     * @param manifest The extension manifest to resolve dependencies for
     * @return ResolutionResult with install order, missing and satisfied dependencies
     */
    public ResolutionResult resolve(ExtensionManifest manifest) {
        return resolveAll(List.of(manifest));
    }

    /**
     * Resolves dependencies for a batch of extension manifests.
     *
     * <p>Performs transitive dependency resolution, circular dependency detection,
     * and returns a topologically sorted install order.</p>
     *
     * @param manifests The list of extension manifests to resolve
     * @return ResolutionResult with install order, missing and satisfied dependencies
     */
    public ResolutionResult resolveAll(List<ExtensionManifest> manifests) {
        List<String> missing = new ArrayList<>();
        List<String> satisfied = new ArrayList<>();
        List<String> installOrder = new ArrayList<>();

        // Build dependency graph
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        Map<String, ExtensionManifest> manifestMap = new LinkedHashMap<>();

        for (ExtensionManifest manifest : manifests) {
            manifestMap.put(manifest.getName(), manifest);
            graph.putIfAbsent(manifest.getName(), new LinkedHashSet<>());
        }

        // Resolve transitive dependencies and populate graph
        Set<String> visited = new HashSet<>();
        for (ExtensionManifest manifest : manifests) {
            resolveTransitive(manifest, graph, manifestMap, missing, satisfied, visited);
        }

        // Check for circular dependencies
        List<String> cycle = detectCircularDependencies(graph);
        if (!cycle.isEmpty()) {
            String cycleStr = String.join(" -> ", cycle);
            log.error("Circular dependency detected: {}", cycleStr);
            return ResolutionResult.failure(
                    "Circular dependency detected: " + cycleStr, missing, satisfied);
        }

        // Topological sort for install order
        try {
            installOrder = topologicalSort(graph);
        } catch (IllegalStateException e) {
            return ResolutionResult.failure(e.getMessage(), missing, satisfied);
        }

        boolean allSatisfied = missing.isEmpty();
        return new ResolutionResult(allSatisfied, missing, satisfied, installOrder, null);
    }

    /**
     * Recursively resolves transitive dependencies.
     */
    private void resolveTransitive(ExtensionManifest manifest,
                                    Map<String, Set<String>> graph,
                                    Map<String, ExtensionManifest> manifestMap,
                                    List<String> missing,
                                    List<String> satisfied,
                                    Set<String> visited) {
        String name = manifest.getName();
        if (visited.contains(name)) {
            return;
        }
        visited.add(name);

        for (ExtensionManifest.Dependency dep : manifest.getDependencies()) {
            graph.computeIfAbsent(name, k -> new LinkedHashSet<>()).add(dep.name());
            graph.putIfAbsent(dep.name(), new LinkedHashSet<>());

            Optional<ExtensionManifest> installed = registry.getLatestByName(dep.name());

            if (installed.isEmpty()) {
                // Check if it's among the manifests being resolved in this batch
                ExtensionManifest inBatch = manifestMap.get(dep.name());
                if (inBatch != null) {
                    satisfied.add(dep.name());
                    resolveTransitive(inBatch, graph, manifestMap, missing, satisfied, visited);
                } else if (!dep.optional()) {
                    missing.add(dep.name() + " (not installed)");
                }
            } else {
                String installedVersion = installed.get().getVersion();
                if (dep.isSatisfiedBy(installedVersion)) {
                    satisfied.add(dep.name());
                    // Resolve transitive deps of installed extension too
                    resolveTransitive(installed.get(), graph, manifestMap,
                            missing, satisfied, visited);
                } else {
                    if (!dep.optional()) {
                        missing.add(dep.name() + " (need " + dep.minVersion()
                                + ", have " + installedVersion + ")");
                    }
                }
            }
        }
    }

    /**
     * Detects circular dependencies in the dependency graph using DFS.
     *
     * @return List representing the cycle path, or empty list if no cycle exists
     */
    private List<String> detectCircularDependencies(Map<String, Set<String>> graph) {
        Set<String> whiteSet = new HashSet<>(graph.keySet()); // unvisited
        Set<String> graySet = new HashSet<>();  // in progress
        Map<String, String> parent = new HashMap<>();

        for (String node : graph.keySet()) {
            if (whiteSet.contains(node)) {
                List<String> cycle = dfs(node, graph, whiteSet, graySet, parent);
                if (!cycle.isEmpty()) {
                    return cycle;
                }
            }
        }
        return List.of();
    }

    private List<String> dfs(String node, Map<String, Set<String>> graph,
                              Set<String> whiteSet, Set<String> graySet,
                              Map<String, String> parent) {
        whiteSet.remove(node);
        graySet.add(node);

        Set<String> neighbors = graph.getOrDefault(node, Set.of());
        for (String neighbor : neighbors) {
            if (graySet.contains(neighbor)) {
                // Found a cycle, reconstruct the path
                List<String> cycle = new ArrayList<>();
                cycle.add(neighbor);
                String current = node;
                while (!current.equals(neighbor)) {
                    cycle.add(current);
                    current = parent.getOrDefault(current, neighbor);
                }
                cycle.add(neighbor);
                Collections.reverse(cycle);
                return cycle;
            }
            if (whiteSet.contains(neighbor)) {
                parent.put(neighbor, node);
                List<String> cycle = dfs(neighbor, graph, whiteSet, graySet, parent);
                if (!cycle.isEmpty()) {
                    return cycle;
                }
            }
        }

        graySet.remove(node);
        return List.of();
    }

    /**
     * Performs topological sort on the dependency graph using Kahn's algorithm.
     *
     * @return List of extension names in install order (dependencies first)
     */
    private List<String> topologicalSort(Map<String, Set<String>> graph) {
        Map<String, Integer> inDegree = new HashMap<>();
        for (String node : graph.keySet()) {
            inDegree.putIfAbsent(node, 0);
        }
        for (Map.Entry<String, Set<String>> entry : graph.entrySet()) {
            for (String dep : entry.getValue()) {
                inDegree.merge(dep, 1, Integer::sum);
            }
        }

        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        List<String> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String node = queue.poll();
            sorted.add(node);
            for (String dep : graph.getOrDefault(node, Set.of())) {
                int newDegree = inDegree.merge(dep, -1, Integer::sum);
                if (newDegree == 0) {
                    queue.add(dep);
                }
            }
        }

        if (sorted.size() != graph.size()) {
            throw new IllegalStateException("Circular dependency detected during topological sort");
        }

        // Reverse so dependencies come first
        Collections.reverse(sorted);
        return sorted;
    }

    /**
     * Result of dependency resolution.
     */
    public record ResolutionResult(
            boolean satisfied,
            List<String> missingDependencies,
            List<String> satisfiedDependencies,
            List<String> installOrder,
            String error
    ) {
        public static ResolutionResult failure(String error, List<String> missing, List<String> satisfied) {
            return new ResolutionResult(false, missing, satisfied, List.of(), error);
        }

        public boolean hasError() {
            return error != null && !error.isEmpty();
        }
    }
}
