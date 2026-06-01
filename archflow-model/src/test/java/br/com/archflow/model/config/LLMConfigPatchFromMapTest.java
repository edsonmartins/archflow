package br.com.archflow.model.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LLMConfigPatch.fromMap")
class LLMConfigPatchFromMapTest {

    @Test
    @DisplayName("null/empty map → empty patch")
    void emptyMap() {
        assertThat(LLMConfigPatch.fromMap(null).isEmpty()).isTrue();
        assertThat(LLMConfigPatch.fromMap(Map.of()).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("reads provider/model/temperature/maxTokens (numbers)")
    void readsNumbers() {
        LLMConfigPatch p = LLMConfigPatch.fromMap(Map.of(
                "provider", "openrouter",
                "model", "openai/gpt-4.1-mini",
                "temperature", 0.5,
                "maxTokens", 4096));

        assertThat(p.provider()).contains("openrouter");
        assertThat(p.model()).contains("openai/gpt-4.1-mini");
        assertThat(p.temperature().getAsDouble()).isEqualTo(0.5);
        assertThat(p.maxTokens().getAsInt()).isEqualTo(4096);
    }

    @Test
    @DisplayName("coerces numeric strings (e.g. from metadata.properties)")
    void coercesStrings() {
        LLMConfigPatch p = LLMConfigPatch.fromMap(Map.of(
                "temperature", "0.2",
                "maxTokens", "1024",
                "timeout", "30000"));

        assertThat(p.temperature().getAsDouble()).isEqualTo(0.2);
        assertThat(p.maxTokens().getAsInt()).isEqualTo(1024);
        assertThat(p.timeout().getAsLong()).isEqualTo(30000L);
    }

    @Test
    @DisplayName("ignores unknown keys and non-numeric junk")
    void ignoresUnknown() {
        Map<String, Object> m = new HashMap<>();
        m.put("escalationThreshold", "0.7");   // unknown LLM key → ignored
        m.put("maxContextMessages", 20);       // unknown → ignored
        m.put("temperature", "not-a-number");  // junk → ignored
        m.put("model", "claude-3-5-sonnet");

        LLMConfigPatch p = LLMConfigPatch.fromMap(m);

        assertThat(p.model()).contains("claude-3-5-sonnet");
        assertThat(p.temperature()).isEmpty();
        assertThat(p.maxTokens()).isEmpty();
    }

    @Test
    @DisplayName("propagates additionalConfig map")
    void additionalConfig() {
        LLMConfigPatch p = LLMConfigPatch.fromMap(Map.of(
                "additionalConfig", Map.of("baseUrl", "https://x/v1")));
        assertThat(p.additionalConfig()).containsEntry("baseUrl", "https://x/v1");
    }
}
