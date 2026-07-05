package br.com.archflow.conversation.governance;

import br.com.archflow.model.config.LLMConfigPatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GovernanceSettings / LLMSettings / GovernanceSnapshot")
class GovernanceSettingsTest {

    @Test
    @DisplayName("defaults() fills all sections")
    void defaults() {
        GovernanceSettings s = GovernanceSettings.defaults();
        assertThat(s.agentEnabled()).isTrue();
        assertThat(s.llm()).isNotNull();
        assertThat(s.guardrails()).isNotNull();
        assertThat(s.rateLimit()).isNotNull();
        assertThat(s.settingsVersion()).isEqualTo(1L);
        assertThat(s.extensions()).isEmpty();
    }

    @Test
    @DisplayName("null sections are normalized to defaults")
    void nullsNormalized() {
        GovernanceSettings s = new GovernanceSettings(true, null, null, null, null, 0, null, null, null);
        assertThat(s.llm()).isEqualTo(LLMSettings.defaults());
        assertThat(s.guardrails()).isNotNull();
        assertThat(s.rateLimit()).isNotNull();
        assertThat(s.settingsVersion()).isEqualTo(1L);   // 0 → 1
        assertThat(s.extensions()).isEmpty();
    }

    @Test
    @DisplayName("LLMSettings.toString masks the apiKey")
    void apiKeyMasked() {
        LLMSettings llm = new LLMSettings(LLMConfigPatch.empty(), "super-secret", 3);
        assertThat(llm.toString()).doesNotContain("super-secret").contains("***");
    }

    @Test
    @DisplayName("GovernanceSnapshot.llmPatch bridges to the tenant LLM patch")
    void snapshotBridgesPatch() {
        LLMConfigPatch patch = LLMConfigPatch.builder().model("anthropic/claude").maxTokens(2048).build();
        GovernanceSettings s = new GovernanceSettings(true, null,
                new LLMSettings(patch, null, 3), null, null, 1L, null, null, null);
        GovernanceSnapshot snap = new GovernanceSnapshot("p1", "t1", s, true, null);

        assertThat(snap.llmPatch().model()).contains("anthropic/claude");
        assertThat(snap.llmPatch().maxTokens().getAsInt()).isEqualTo(2048);
    }
}
