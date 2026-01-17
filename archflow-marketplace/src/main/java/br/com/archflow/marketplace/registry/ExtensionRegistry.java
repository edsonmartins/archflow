package br.com.archflow.marketplace.registry;

import br.com.archflow.marketplace.manifest.ExtensionManifest;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Registry for managing installed extensions.
 *
 * <p>This registry maintains a catalog of all installed extensions,
 * their manifests, and provides lookup and query capabilities.</p>
 *
 * <p>Features:
 * <ul>
 *   <li>Register/unregister extensions</li>
 *   <li>Query by name, version, category, keywords</li>
 *   <li>Check compatibility</li>
 *   <li>Event notifications for extension lifecycle</li>
 * </ul>
 */
public class ExtensionRegistry {

    private static volatile ExtensionRegistry instance;

    private final Map<String, ExtensionManifest> extensionsById;
    private final Map<String, List<ExtensionManifest>> extensionsByName;
    private final Map<String, Set<String>> extensionsByCategory;
    private final Map<String, Set<String>> extensionsByKeyword;
    private final Map<String, ExtensionManifest> extensionsByEntryPoint;
    private final List<Consumer<ExtensionEvent>> eventListeners;

    private ExtensionRegistry() {
        this.extensionsById = new ConcurrentHashMap<>();
        this.extensionsByName = new ConcurrentHashMap<>();
        this.extensionsByCategory = new ConcurrentHashMap<>();
        this.extensionsByKeyword = new ConcurrentHashMap<>();
        this.extensionsByEntryPoint = new ConcurrentHashMap<>();
        this.eventListeners = new CopyOnWriteArrayList<>();
    }

    /**
     * Gets the singleton instance.
     */
    public static ExtensionRegistry getInstance() {
        if (instance == null) {
            synchronized (ExtensionRegistry.class) {
                if (instance == null) {
                    instance = new ExtensionRegistry();
                }
            }
        }
        return instance;
    }

    /**
     * Resets the singleton (mainly for testing).
     */
    public static synchronized void reset() {
        instance = null;
    }

    /**
     * Registers an extension from its manifest.
     *
     * @param manifest The extension manifest
     * @return true if registered successfully, false if already exists
     */
    public boolean register(ExtensionManifest manifest) {
        String id = manifest.getId();

        if (extensionsById.containsKey(id)) {
            return false;
        }

        extensionsById.put(id, manifest);
        extensionsByEntryPoint.put(manifest.getEntryPoint(), manifest);

        // Index by name
        extensionsByName.computeIfAbsent(manifest.getName(), k -> new ArrayList<>()).add(manifest);

        // Index by category
        for (String category : manifest.getCategories()) {
            extensionsByCategory.computeIfAbsent(category, k -> ConcurrentHashMap.newKeySet()).add(id);
        }

        // Index by keyword
        for (String keyword : manifest.getKeywords()) {
            extensionsByKeyword.computeIfAbsent(keyword.toLowerCase(), k -> ConcurrentHashMap.newKeySet()).add(id);
        }

        publishEvent(new ExtensionEvent(
                ExtensionEventType.REGISTERED,
                id,
                manifest.getName(),
                manifest.getVersion()
        ));

        return true;
    }

    /**
     * Unregisters an extension.
     *
     * @param id The extension ID (name:version)
     * @return The unregistered manifest, or null if not found
     */
    public ExtensionManifest unregister(String id) {
        ExtensionManifest manifest = extensionsById.remove(id);

        if (manifest != null) {
            extensionsByEntryPoint.remove(manifest.getEntryPoint());

            // Remove from name index
            List<ExtensionManifest> byName = extensionsByName.get(manifest.getName());
            if (byName != null) {
                byName.remove(manifest);
                if (byName.isEmpty()) {
                    extensionsByName.remove(manifest.getName());
                }
            }

            // Remove from category index
            for (String category : manifest.getCategories()) {
                Set<String> ids = extensionsByCategory.get(category);
                if (ids != null) {
                    ids.remove(id);
                    if (ids.isEmpty()) {
                        extensionsByCategory.remove(category);
                    }
                }
            }

            // Remove from keyword index
            for (String keyword : manifest.getKeywords()) {
                Set<String> ids = extensionsByKeyword.get(keyword.toLowerCase());
                if (ids != null) {
                    ids.remove(id);
                    if (ids.isEmpty()) {
                        extensionsByKeyword.remove(keyword.toLowerCase());
                    }
                }
            }

            publishEvent(new ExtensionEvent(
                    ExtensionEventType.UNREGISTERED,
                    id,
                    manifest.getName(),
                    manifest.getVersion()
            ));
        }

        return manifest;
    }

    /**
     * Gets an extension by its ID.
     */
    public Optional<ExtensionManifest> getById(String id) {
        return Optional.ofNullable(extensionsById.get(id));
    }

    /**
     * Gets all versions of an extension by name.
     */
    public List<ExtensionManifest> getByName(String name) {
        List<ExtensionManifest> manifests = extensionsByName.get(name);
        return manifests != null ? List.copyOf(manifests) : List.of();
    }

    /**
     * Gets the latest version of an extension by name.
     */
    public Optional<ExtensionManifest> getLatestByName(String name) {
        List<ExtensionManifest> manifests = extensionsByName.get(name);
        if (manifests == null || manifests.isEmpty()) {
            return Optional.empty();
        }
        return manifests.stream()
                .max(Comparator.comparing(this::parseVersion));
    }

    /**
     * Gets an extension by its entry point class name.
     */
    public Optional<ExtensionManifest> getByEntryPoint(String entryPoint) {
        return Optional.ofNullable(extensionsByEntryPoint.get(entryPoint));
    }

    /**
     * Gets all extensions in a category.
     */
    public List<ExtensionManifest> getByCategory(String category) {
        Set<String> ids = extensionsByCategory.get(category);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream()
                .map(extensionsById::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Searches extensions by keyword.
     */
    public List<ExtensionManifest> searchByKeyword(String keyword) {
        Set<String> ids = extensionsByKeyword.get(keyword.toLowerCase());
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream()
                .map(extensionsById::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Searches extensions by text in name, description, or keywords.
     */
    public List<ExtensionManifest> search(String query) {
        String lowerQuery = query.toLowerCase();

        return extensionsById.values().stream()
                .filter(m -> m.getName().toLowerCase().contains(lowerQuery) ||
                           m.getDescription().toLowerCase().contains(lowerQuery) ||
                           m.getKeywords().stream().anyMatch(k -> k.toLowerCase().contains(lowerQuery)))
                .collect(Collectors.toList());
    }

    /**
     * Gets all registered extensions.
     */
    public List<ExtensionManifest> getAll() {
        return List.copyOf(extensionsById.values());
    }

    /**
     * Gets all categories.
     */
    public Set<String> getCategories() {
        return extensionsByCategory.keySet();
    }

    /**
     * Gets the total number of registered extensions.
     */
    public int size() {
        return extensionsById.size();
    }

    /**
     * Checks if an extension is registered.
     */
    public boolean isRegistered(String id) {
        return extensionsById.containsKey(id);
    }

    /**
     * Checks if an extension with a specific entry point is registered.
     */
    public boolean hasEntryPoint(String entryPoint) {
        return extensionsByEntryPoint.containsKey(entryPoint);
    }

    /**
     * Gets all extensions that require a specific permission.
     */
    public List<ExtensionManifest> getByPermission(String permission) {
        return extensionsById.values().stream()
                .filter(m -> m.requiresPermission(permission))
                .collect(Collectors.toList());
    }

    /**
     * Gets all extensions with dangerous permissions.
     */
    public List<ExtensionManifest> getDangerousExtensions() {
        return extensionsById.values().stream()
                .filter(ExtensionManifest::hasDangerousPermissions)
                .collect(Collectors.toList());
    }

    /**
     * Adds an event listener.
     */
    public void addListener(Consumer<ExtensionEvent> listener) {
        eventListeners.add(listener);
    }

    /**
     * Removes an event listener.
     */
    public void removeListener(Consumer<ExtensionEvent> listener) {
        eventListeners.remove(listener);
    }

    /**
     * Gets registry statistics.
     */
    public ExtensionStats getStats() {
        Map<String, Long> byType = extensionsById.values().stream()
                .collect(Collectors.groupingBy(ExtensionManifest::getType, Collectors.counting()));

        return new ExtensionStats(
                extensionsById.size(),
                extensionsByName.size(),
                extensionsByCategory.size(),
                extensionsById.values().stream()
                        .mapToLong(m -> m.getDependencies().size())
                        .sum(),
                byType.getOrDefault(ExtensionManifest.ExtensionType.TOOL, 0L).intValue(),
                byType.getOrDefault(ExtensionManifest.ExtensionType.AGENT, 0L).intValue(),
                byType.getOrDefault(ExtensionManifest.ExtensionType.CHAIN, 0L).intValue()
        );
    }

    private long parseVersion(ExtensionManifest m) {
        try {
            String[] parts = m.getVersion().split("\\.");
            long version = 0;
            for (int i = 0; i < parts.length && i < 4; i++) {
                version = version * 1000 + Long.parseLong(parts[i]);
            }
            return version;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void publishEvent(ExtensionEvent event) {
        eventListeners.forEach(listener -> {
            try {
                listener.accept(event);
            } catch (Exception e) {
                // Log error but continue notifying other listeners
                System.err.println("Error notifying extension listener: " + e.getMessage());
            }
        });
    }

    /**
     * Event types for extension lifecycle.
     */
    public enum ExtensionEventType {
        REGISTERED,
        UNREGISTERED,
        UPDATED,
        ENABLED,
        DISABLED
    }

    /**
     * Event published when extension lifecycle changes.
     */
    public record ExtensionEvent(
            ExtensionEventType type,
            String extensionId,
            String extensionName,
            String extensionVersion,
            java.time.Instant timestamp
    ) {
        public ExtensionEvent(ExtensionEventType type, String extensionId, String extensionName, String extensionVersion) {
            this(type, extensionId, extensionName, extensionVersion, java.time.Instant.now());
        }
    }

    /**
     * Statistics about the extension registry.
     */
    public record ExtensionStats(
            int totalExtensions,
            int uniqueNames,
            int categories,
            long totalDependencies,
            int toolCount,
            int agentCount,
            int chainCount
    ) {}
}
