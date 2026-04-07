package br.com.archflow.langchain4j.skills;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("SkillsManager")
class SkillsManagerTest {

    private SkillsManager manager;

    @BeforeEach
    void setUp() {
        manager = new SkillsManager();
    }

    @Test
    @DisplayName("should register and retrieve skill")
    void shouldRegisterAndRetrieve() {
        manager.register(Skill.of("docx", "Edit docs", "Instructions"));

        assertThat(manager.getSkill("docx")).isPresent();
        assertThat(manager.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("should activate and deactivate skill")
    void shouldActivateAndDeactivate() {
        manager.register(Skill.of("docx", "Edit docs", "Instructions"));

        Skill activated = manager.activateSkill("docx");
        assertThat(activated.name()).isEqualTo("docx");
        assertThat(manager.isActive("docx")).isTrue();

        manager.deactivateSkill("docx");
        assertThat(manager.isActive("docx")).isFalse();
    }

    @Test
    @DisplayName("should throw when activating unknown skill")
    void shouldThrowForUnknownSkill() {
        assertThatThrownBy(() -> manager.activateSkill("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    @DisplayName("should list all registered skills")
    void shouldListAll() {
        manager.register(Skill.of("a", "A", "content"));
        manager.register(Skill.of("b", "B", "content"));

        assertThat(manager.listSkills()).hasSize(2);
    }

    @Test
    @DisplayName("should list only active skills")
    void shouldListActive() {
        manager.register(Skill.of("a", "A", "content"));
        manager.register(Skill.of("b", "B", "content"));
        manager.activateSkill("a");

        List<Skill> active = manager.listActiveSkills();
        assertThat(active).hasSize(1);
        assertThat(active.get(0).name()).isEqualTo("a");
    }

    @Test
    @DisplayName("should format available skills for LLM")
    void shouldFormatForLlm() {
        manager.register(Skill.of("docx", "Edit Word documents", "content"));
        manager.register(Skill.of("calc", "Math calculations", "content"));

        String formatted = manager.formatAvailableSkills();
        assertThat(formatted).contains("docx");
        assertThat(formatted).contains("calc");
        assertThat(formatted).contains("Available skills:");
    }

    @Test
    @DisplayName("should get skill resource")
    void shouldGetSkillResource() {
        var resource = SkillResource.of("template.txt", "Template content");
        manager.register(new Skill("docx", "Edit docs", "Instructions", List.of(resource)));

        var found = manager.getSkillResource("docx", "template.txt");
        assertThat(found).isPresent();
        assertThat(found.get().content()).isEqualTo("Template content");
    }

    @Test
    @DisplayName("should return empty for unknown resource")
    void shouldReturnEmptyForUnknownResource() {
        manager.register(Skill.of("docx", "Edit docs", "Instructions"));

        assertThat(manager.getSkillResource("docx", "unknown.txt")).isEmpty();
        assertThat(manager.getSkillResource("unknown", "file.txt")).isEmpty();
    }

    @Test
    @DisplayName("should clear all skills")
    void shouldClearAll() {
        manager.register(Skill.of("a", "A", "content"));
        manager.activateSkill("a");

        manager.clear();

        assertThat(manager.size()).isZero();
        assertThat(manager.isActive("a")).isFalse();
    }

    @Test
    @DisplayName("should load from loader")
    void shouldLoadFromLoader() throws Exception {
        SkillLoader mockLoader = () -> List.of(
                Skill.of("a", "A", "content"),
                Skill.of("b", "B", "content")
        );

        int count = manager.loadFrom(mockLoader);

        assertThat(count).isEqualTo(2);
        assertThat(manager.size()).isEqualTo(2);
    }
}
