package br.com.archflow.agent.governance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

@DisplayName("GovernanceProfile")
class GovernanceProfileTest {
    @Test @DisplayName("should allow all tools when enabledTools is empty")
    void shouldAllowAllWhenEmpty() {
        var p = GovernanceProfile.builder().id("t").name("T").build();
        assertThat(p.isToolAllowed("any")).isTrue();
    }
    @Test @DisplayName("should restrict to enabled tools")
    void shouldRestrict() {
        var p = GovernanceProfile.builder().id("t").name("T").enableTool("tracking").build();
        assertThat(p.isToolAllowed("tracking")).isTrue();
        assertThat(p.isToolAllowed("complaints")).isFalse();
    }
    @Test @DisplayName("should block disabled tools")
    void shouldBlock() {
        var p = GovernanceProfile.builder().id("t").name("T").disableTool("dangerous").build();
        assertThat(p.isToolAllowed("safe")).isTrue();
        assertThat(p.isToolAllowed("dangerous")).isFalse();
    }
    @Test @DisplayName("should build system prompt with instructions")
    void shouldBuildPrompt() {
        var p = GovernanceProfile.builder().id("t").name("T")
                .systemPrompt("Base.").customInstructions("Extra.").build();
        assertThat(p.buildSystemPrompt()).contains("Base.").contains("Extra.");
    }
    @Test @DisplayName("should check escalation")
    void shouldCheckEscalation() {
        var p = GovernanceProfile.builder().id("t").name("T").escalationThreshold(0.5).build();
        assertThat(p.shouldEscalate(0.3)).isTrue();
        assertThat(p.shouldEscalate(0.7)).isFalse();
    }
    @Test @DisplayName("should reject invalid threshold")
    void shouldRejectInvalid() {
        assertThatThrownBy(() -> GovernanceProfile.builder().id("t").name("T").escalationThreshold(1.5).build())
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test @DisplayName("should defensive copy collections")
    void shouldDefensiveCopy() {
        var tools = new java.util.HashSet<>(Set.of("a"));
        var p = GovernanceProfile.builder().id("t").name("T").enableTools(tools).build();
        tools.add("b");
        assertThat(p.enabledTools()).hasSize(1);
    }
}
