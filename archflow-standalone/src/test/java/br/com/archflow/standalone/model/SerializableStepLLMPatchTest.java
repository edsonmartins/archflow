package br.com.archflow.standalone.model;

import br.com.archflow.model.config.LLMConfigPatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SerializableStep.getLLMPatch — config Map → patch")
class SerializableStepLLMPatchTest {

    private static SerializableStep stepWithConfig(Map<String, Object> config) {
        SerializableStep step = new SerializableStep();
        step.setConfiguration(config);
        return step;
    }

    @Test
    @DisplayName("config vazio resolve em patch vazio")
    void emptyConfig() {
        assertThat(stepWithConfig(Map.of()).getLLMPatch().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("mapeia model/provider/temperature/maxTokens")
    void mapsCommonFields() {
        LLMConfigPatch patch = stepWithConfig(Map.of(
                "provider", "openrouter",
                "model", "openai/gpt-4.1-mini",
                "temperature", 0.5,
                "maxTokens", 4096
        )).getLLMPatch();

        assertThat(patch.provider()).contains("openrouter");
        assertThat(patch.model()).contains("openai/gpt-4.1-mini");
        assertThat(patch.temperature().getAsDouble()).isEqualTo(0.5);
        assertThat(patch.maxTokens().getAsInt()).isEqualTo(4096);
        assertThat(patch.timeout()).isEmpty();
    }

    @Test
    @DisplayName("override só de maxTokens deixa o resto vazio (para herdar)")
    void onlyMaxTokens() {
        LLMConfigPatch patch = stepWithConfig(Map.of("maxTokens", 8192)).getLLMPatch();

        assertThat(patch.maxTokens().getAsInt()).isEqualTo(8192);
        assertThat(patch.model()).isEmpty();
        assertThat(patch.provider()).isEmpty();
        assertThat(patch.temperature()).isEmpty();
    }

    @Test
    @DisplayName("aceita maxTokens como inteiro vindo de JSON (Number)")
    void numericCoercion() {
        // Jackson pode desserializar como Integer/Long/Double
        LLMConfigPatch patch = stepWithConfig(Map.of(
                "temperature", 1,          // Integer
                "maxTokens", 2048L,        // Long
                "timeout", 60               // Integer (segundos, repassado cru)
        )).getLLMPatch();

        assertThat(patch.temperature().getAsDouble()).isEqualTo(1.0);
        assertThat(patch.maxTokens().getAsInt()).isEqualTo(2048);
        assertThat(patch.timeout().getAsLong()).isEqualTo(60L);
    }

    @Test
    @DisplayName("o campo único configuration aceita a chave legada 'config' via @JsonAlias")
    void configAliasDeserializesIntoConfiguration() throws Exception {
        // A legacy document uses `config`; the single `configuration` field must
        // absorb it so there are never two divergent config maps.
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        SerializableStep step = mapper.readValue(
                "{\"id\":\"s1\",\"config\":{\"model\":\"gpt-4\",\"temperature\":0.2}}",
                SerializableStep.class);

        assertThat(step.getConfiguration()).containsEntry("model", "gpt-4");
        assertThat(step.getLLMPatch().temperature().getAsDouble()).isEqualTo(0.2);
    }

    @Test
    @DisplayName("propaga additionalConfig")
    void additionalConfig() {
        LLMConfigPatch patch = stepWithConfig(Map.of(
                "additionalConfig", Map.of("baseUrl", "https://x/v1")
        )).getLLMPatch();

        assertThat(patch.additionalConfig()).containsEntry("baseUrl", "https://x/v1");
    }
}
