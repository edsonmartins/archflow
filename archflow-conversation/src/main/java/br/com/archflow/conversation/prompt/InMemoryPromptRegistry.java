package br.com.archflow.conversation.prompt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory implementation of {@link PromptRegistry}.
 *
 * <p>Storage layout:
 * <pre>
 *   storage: tenantId → promptId → List&lt;PromptVersion&gt;
 * </pre>
 *
 * <p>Thread-safe via {@link ConcurrentHashMap} and synchronized blocks
 * on the per-prompt lists.
 */
public class InMemoryPromptRegistry implements PromptRegistry {

    private final Map<String, Map<String, List<PromptVersion>>> storage = new ConcurrentHashMap<>();

    @Override
    public PromptVersion register(String tenantId, String promptId, String template) {
        Map<String, List<PromptVersion>> tenantPrompts = storage.computeIfAbsent(tenantId, k -> new ConcurrentHashMap<>());
        List<PromptVersion> versions = tenantPrompts.computeIfAbsent(promptId, k -> new ArrayList<>());

        synchronized (versions) {
            int nextVersion = versions.size() + 1;
            boolean active = versions.isEmpty();
            PromptVersion v = new PromptVersion(promptId, tenantId, nextVersion, template, active, null);
            versions.add(v);
            return v;
        }
    }

    @Override
    public Optional<PromptVersion> getActive(String tenantId, String promptId) {
        return getVersionsList(tenantId, promptId).stream()
                .filter(PromptVersion::active)
                .findFirst();
    }

    @Override
    public Optional<PromptVersion> getVersion(String tenantId, String promptId, int version) {
        return getVersionsList(tenantId, promptId).stream()
                .filter(v -> v.version() == version)
                .findFirst();
    }

    @Override
    public List<PromptVersion> listVersions(String tenantId, String promptId) {
        return getVersionsList(tenantId, promptId).stream()
                .sorted(Comparator.comparingInt(PromptVersion::version).reversed())
                .toList();
    }

    @Override
    public void activateVersion(String tenantId, String promptId, int version) {
        Map<String, List<PromptVersion>> tenantPrompts = storage.get(tenantId);
        if (tenantPrompts == null) return;
        List<PromptVersion> versions = tenantPrompts.get(promptId);
        if (versions == null) return;
        synchronized (versions) {
            for (int i = 0; i < versions.size(); i++) {
                PromptVersion v = versions.get(i);
                if (v.version() == version) {
                    versions.set(i, v.withActive(true));
                } else if (v.active()) {
                    versions.set(i, v.withActive(false));
                }
            }
        }
    }

    @Override
    public List<String> listPromptIds(String tenantId) {
        Map<String, List<PromptVersion>> tenantPrompts = storage.get(tenantId);
        if (tenantPrompts == null) return List.of();
        return tenantPrompts.keySet().stream().sorted().collect(Collectors.toList());
    }

    private List<PromptVersion> getVersionsList(String tenantId, String promptId) {
        Map<String, List<PromptVersion>> tenantPrompts = storage.get(tenantId);
        if (tenantPrompts == null) return List.of();
        List<PromptVersion> versions = tenantPrompts.get(promptId);
        if (versions == null) return List.of();
        // Synchronize on the same monitor as mutating methods so readers
        // never observe a transient state with zero or two active versions.
        synchronized (versions) {
            return new ArrayList<>(versions);
        }
    }
}
