package br.com.archflow.api.skills.impl;

import br.com.archflow.api.config.TenantContext;
import br.com.archflow.api.skills.SkillsController;
import br.com.archflow.api.skills.dto.SkillDto;
import br.com.archflow.langchain4j.skills.Skill;
import br.com.archflow.langchain4j.skills.SkillResource;
import br.com.archflow.langchain4j.skills.SkillsManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link SkillsController} backed by a
 * {@link SkillsManager}. Active state is tracked here (not in the
 * manager), partitioned by tenant so each tenant has its own active
 * skill set. {@code SkillsManager.activateSkill} mutates internal
 * manager state per-call but returns the {@link Skill} snapshot,
 * making "active" observable only via this side store.
 */
public class SkillsControllerImpl implements SkillsController {

    private final SkillsManager manager;
    /** Mirrors manager's active set per-tenant so {@code SkillDto.active} is correct on list. */
    private final Map<String, Set<String>> activeByTenant = new ConcurrentHashMap<>();

    public SkillsControllerImpl(SkillsManager manager) {
        this.manager = manager;
    }

    private Set<String> activeForCurrentTenant() {
        return activeByTenant.computeIfAbsent(
                TenantContext.currentTenantId(),
                k -> ConcurrentHashMap.newKeySet());
    }

    @Override
    public List<SkillDto> listSkills() {
        Set<String> active = activeForCurrentTenant();
        return manager.listSkills().stream()
                .map(s -> toDto(s, active.contains(s.name()), false))
                .toList();
    }

    @Override
    public List<SkillDto> listActiveSkills() {
        Set<String> active = activeForCurrentTenant();
        return manager.listSkills().stream()
                .filter(s -> active.contains(s.name()))
                .map(s -> toDto(s, true, true))
                .toList();
    }

    @Override
    public synchronized SkillDto activate(String name) {
        Skill skill = manager.activateSkill(name);
        activeForCurrentTenant().add(name);
        return toDto(skill, true, true);
    }

    @Override
    public synchronized void deactivate(String name) {
        manager.deactivateSkill(name);
        activeForCurrentTenant().remove(name);
    }

    @Override
    public Optional<SkillDto.SkillResourceDto> getResource(String skillName, String resourceName) {
        return manager.getSkillResource(skillName, resourceName)
                .map(r -> new SkillDto.SkillResourceDto(r.name(), r.mimeType(), r.content()));
    }

    private SkillDto toDto(Skill s, boolean active, boolean includeContent) {
        List<SkillDto.SkillResourceDto> resources = s.resources() == null ? List.of() :
                s.resources().stream()
                        .map(r -> new SkillDto.SkillResourceDto(r.name(), r.mimeType(),
                                includeContent ? r.content() : null))
                        .toList();
        return new SkillDto(
                s.name(),
                s.description(),
                includeContent ? s.content() : null,
                resources,
                active);
    }
}
