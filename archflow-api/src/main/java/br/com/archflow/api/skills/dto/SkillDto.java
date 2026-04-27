package br.com.archflow.api.skills.dto;

import java.util.List;

/**
 * Representation of a skill exposed by the
 * {@code archflow-langchain4j-skills} adapter.
 *
 * @param name        stable identifier
 * @param description human-readable short description
 * @param content     prompt/manifest body (may be {@code null} on list)
 * @param resources   named resources attached to the skill
 * @param active      whether the skill is currently activated
 */
public record SkillDto(
        String name,
        String description,
        String content,
        List<SkillResourceDto> resources,
        boolean active) {

    public record SkillResourceDto(
            String name,
            String mimeType,
            String content) {}
}
