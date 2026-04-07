package br.com.archflow.langchain4j.skills;

import br.com.archflow.model.engine.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

@DisplayName("SkillsAdapter")
class SkillsAdapterTest {

    private SkillsAdapter adapter;
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        adapter = new SkillsAdapter();
        adapter.configure(Map.of()); // No directory — register skills programmatically
        context = mock(ExecutionContext.class);

        // Register test skills
        adapter.getManager().register(Skill.of("docx", "Edit Word documents", "Track changes instructions"));
        adapter.getManager().register(new Skill("calc", "Math calculations", "Step-by-step math",
                List.of(SkillResource.of("formulas.txt", "a^2 + b^2 = c^2"))));
    }

    @Test
    @DisplayName("should list all available skills")
    void shouldListSkills() throws Exception {
        @SuppressWarnings("unchecked")
        List<Map<String, String>> result = (List<Map<String, String>>) adapter.execute("list_skills", null, context);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(m -> m.get("name")).contains("docx", "calc");
    }

    @Test
    @DisplayName("should activate skill and return content")
    void shouldActivateSkill() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) adapter.execute("activate_skill", "docx", context);

        assertThat(result.get("name")).isEqualTo("docx");
        assertThat(result.get("content")).isEqualTo("Track changes instructions");
        assertThat(adapter.getManager().isActive("docx")).isTrue();
    }

    @Test
    @DisplayName("should activate skill via map input")
    void shouldActivateSkillViaMap() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) adapter.execute("activate_skill",
                Map.of("name", "calc"), context);

        assertThat(result.get("name")).isEqualTo("calc");
    }

    @Test
    @DisplayName("should deactivate skill")
    void shouldDeactivateSkill() throws Exception {
        adapter.execute("activate_skill", "docx", context);
        adapter.execute("deactivate_skill", "docx", context);

        assertThat(adapter.getManager().isActive("docx")).isFalse();
    }

    @Test
    @DisplayName("should read skill resource")
    void shouldReadSkillResource() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) adapter.execute("read_skill_resource",
                Map.of("skill", "calc", "resource", "formulas.txt"), context);

        assertThat(result.get("content")).isEqualTo("a^2 + b^2 = c^2");
    }

    @Test
    @DisplayName("should get active skills")
    void shouldGetActiveSkills() throws Exception {
        adapter.execute("activate_skill", "docx", context);

        @SuppressWarnings("unchecked")
        List<Map<String, String>> result = (List<Map<String, String>>) adapter.execute("get_active_skills", null, context);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("name")).isEqualTo("docx");
    }

    @Test
    @DisplayName("should throw for unsupported operation")
    void shouldThrowForUnsupportedOperation() {
        assertThatThrownBy(() -> adapter.execute("unknown_op", null, context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown_op");
    }

    @Test
    @DisplayName("should throw for unknown skill activation")
    void shouldThrowForUnknownSkill() {
        assertThatThrownBy(() -> adapter.execute("activate_skill", "nonexistent", context))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should throw for unknown resource")
    void shouldThrowForUnknownResource() {
        assertThatThrownBy(() -> adapter.execute("read_skill_resource",
                Map.of("skill", "docx", "resource", "missing.txt"), context))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should throw when not configured")
    void shouldThrowWhenNotConfigured() {
        SkillsAdapter unconfigured = new SkillsAdapter();

        assertThatThrownBy(() -> unconfigured.execute("list_skills", null, context))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("should shutdown cleanly")
    void shouldShutdownCleanly() throws Exception {
        adapter.execute("activate_skill", "docx", context);
        adapter.shutdown();

        assertThatThrownBy(() -> adapter.execute("list_skills", null, context))
                .isInstanceOf(IllegalStateException.class);
    }
}
