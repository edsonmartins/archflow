package br.com.archflow.api.skills.impl;

import br.com.archflow.api.skills.SkillsController;
import br.com.archflow.api.skills.dto.SkillDto;
import br.com.archflow.langchain4j.skills.Skill;
import br.com.archflow.langchain4j.skills.SkillResource;
import br.com.archflow.langchain4j.skills.SkillsManager;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Default {@link SkillsController} backed by a
 * {@link SkillsManager}. Active state is tracked here (not in the
 * manager) so reads/writes are visible across HTTP requests —
 * {@code SkillsManager.activateSkill} mutates internal state per-call
 * but returns the {@link Skill} snapshot, making "active" observable
 * only via {@link SkillsManager#listActiveSkills()}.
 */
public class SkillsControllerImpl implements SkillsController {

    private final SkillsManager manager;
    /** Mirrors manager's active set so {@code SkillDto.active} is correct on list. */
    private final Set<String> activeNames = new CopyOnWriteArraySet<>();

    public SkillsControllerImpl(SkillsManager manager) {
        this.manager = manager;
    }

    @Override
    public List<SkillDto> listSkills() {
        return manager.listSkills().stream()
                .map(s -> toDto(s, activeNames.contains(s.name()), false))
                .toList();
    }

    @Override
    public List<SkillDto> listActiveSkills() {
        return manager.listActiveSkills().stream()
                .map(s -> toDto(s, true, true))
                .toList();
    }

    @Override
    public synchronized SkillDto activate(String name) {
        Skill skill = manager.activateSkill(name);
        activeNames.add(name);
        return toDto(skill, true, true);
    }

    @Override
    public synchronized void deactivate(String name) {
        manager.deactivateSkill(name);
        activeNames.remove(name);
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
