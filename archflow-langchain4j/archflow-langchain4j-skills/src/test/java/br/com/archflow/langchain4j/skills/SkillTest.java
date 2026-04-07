package br.com.archflow.langchain4j.skills;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Skill")
class SkillTest {

    @Test
    @DisplayName("should create skill with all fields")
    void shouldCreateSkill() {
        var resource = SkillResource.of("template.txt", "Template content");
        var skill = new Skill("docx", "Edit Word docs", "Instructions here", List.of(resource));

        assertThat(skill.name()).isEqualTo("docx");
        assertThat(skill.description()).isEqualTo("Edit Word docs");
        assertThat(skill.content()).isEqualTo("Instructions here");
        assertThat(skill.resources()).hasSize(1);
    }

    @Test
    @DisplayName("should create skill without resources via factory")
    void shouldCreateSkillWithoutResources() {
        var skill = Skill.of("calc", "Calculator", "Math instructions");

        assertThat(skill.resources()).isEmpty();
    }

    @Test
    @DisplayName("should reject null name")
    void shouldRejectNullName() {
        assertThatThrownBy(() -> Skill.of(null, "desc", "content"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should reject null description")
    void shouldRejectNullDescription() {
        assertThatThrownBy(() -> Skill.of("name", null, "content"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should format for LLM")
    void shouldFormatForLlm() {
        var skill = Skill.of("docx", "Edit Word documents", "content");

        assertThat(skill.formatForLlm()).isEqualTo("- **docx**: Edit Word documents");
    }

    @Test
    @DisplayName("should make defensive copy of resources list")
    void shouldDefensiveCopyResources() {
        var resources = new java.util.ArrayList<>(List.of(SkillResource.of("a", "b")));
        var skill = new Skill("x", "y", "z", resources);
        resources.add(SkillResource.of("c", "d")); // Modify original

        assertThat(skill.resources()).hasSize(1); // Should not be affected
    }
}
