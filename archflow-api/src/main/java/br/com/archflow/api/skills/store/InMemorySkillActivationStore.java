package br.com.archflow.api.skills.store;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link SkillActivationStore} em memória, para dev e testes: não sobrevive a restart. O durável é o
 * {@link JdbcSkillActivationStore}, ligado por {@code archflow.persistence.jdbc.enabled=true}.
 */
public final class InMemorySkillActivationStore implements SkillActivationStore {

    private final Map<String, Set<String>> activeByTenant = new ConcurrentHashMap<>();

    @Override
    public Set<String> activeSkills(String tenantId) {
        Set<String> active = activeByTenant.get(tenantId);
        return active == null ? Set.of() : Set.copyOf(active);
    }

    @Override
    public void activate(String tenantId, String skillName) {
        activeByTenant.computeIfAbsent(tenantId, k -> ConcurrentHashMap.newKeySet()).add(skillName);
    }

    @Override
    public void deactivate(String tenantId, String skillName) {
        Set<String> active = activeByTenant.get(tenantId);
        if (active != null) {
            active.remove(skillName);
        }
    }
}
