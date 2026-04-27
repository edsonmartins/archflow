package br.com.archflow.api.skills;

import br.com.archflow.api.skills.dto.SkillDto;

import java.util.List;
import java.util.Optional;

/**
 * Admin-facing controller over the
 * {@code archflow-langchain4j-skills} adapter. Lets the UI list the
 * available skill set, activate/deactivate entries per request, and
 * fetch specific resources attached to a skill.
 */
public interface SkillsController {

    List<SkillDto> listSkills();

    List<SkillDto> listActiveSkills();

    SkillDto activate(String name);

    void deactivate(String name);

    Optional<SkillDto.SkillResourceDto> getResource(String skillName, String resourceName);
}
