package br.com.archflow.api.skills.impl;

import br.com.archflow.api.config.TenantContext;
import br.com.archflow.api.skills.SkillsController;
import br.com.archflow.api.skills.dto.SkillDto;
import br.com.archflow.api.skills.store.SkillActivationStore;
import br.com.archflow.langchain4j.skills.Skill;
import br.com.archflow.langchain4j.skills.SkillsManager;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Default {@link SkillsController}: o catálogo vem do {@link SkillsManager} (o diretório de skills,
 * igual para a instância) e o estado ativo/inativo vem do {@link SkillActivationStore}, por tenant.
 *
 * <p>O {@code activateSkill}/{@code deactivateSkill} do manager não é chamado: ele mexe num set global,
 * então a escolha de um tenant ligava a skill para todos (RCHFLW-66). O tenant é sempre o da requisição
 * ({@link TenantContext}).
 *
 * <p>Hoje nenhum agente nem nó de fluxo lê skills; quem passar a ler deve partir de
 * {@link #listActiveSkills()} (ou do store com o tenant da execução), nunca do set global do manager.
 */
public class SkillsControllerImpl implements SkillsController {

    private final SkillsManager manager;
    private final SkillActivationStore activation;

    public SkillsControllerImpl(SkillsManager manager, SkillActivationStore activation) {
        this.manager = manager;
        this.activation = activation;
    }

    private Set<String> activeForCurrentTenant() {
        return activation.activeSkills(TenantContext.currentTenantId());
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
        // Filtra pelo catálogo carregado: uma skill ligada que saiu do diretório não aparece.
        Set<String> active = activeForCurrentTenant();
        return manager.listSkills().stream()
                .filter(s -> active.contains(s.name()))
                .map(s -> toDto(s, true, true))
                .toList();
    }

    @Override
    public SkillDto activate(String name) {
        Skill skill = manager.getSkill(name)
                .orElseThrow(() -> new IllegalArgumentException("Skill not found: " + name));
        activation.activate(TenantContext.currentTenantId(), name);
        return toDto(skill, true, true);
    }

    @Override
    public void deactivate(String name) {
        activation.deactivate(TenantContext.currentTenantId(), name);
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
