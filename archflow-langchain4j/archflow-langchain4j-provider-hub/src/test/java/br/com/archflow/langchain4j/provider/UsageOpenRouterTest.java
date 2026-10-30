package br.com.archflow.langchain4j.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("telemetria de custo OpenRouter")
class UsageOpenRouterTest {

    @Test
    @DisplayName("solicita usage.cost e preserva reasoning no mesmo corpo")
    void incluiCustoEReasoning() {
        var config = LLMProviderConfig.builder(LLMProvider.OPENROUTER)
                .modelId("google/gemini-2.5-flash-lite")
                .reasoning(Map.of("effort", "none"))
                .build();

        assertThat(LLMProviderHub.parametrosDoCorpo(config))
                .containsEntry("usage", Map.of("include", true))
                .containsEntry("reasoning", Map.of("effort", "none"));
    }

    @Test
    @DisplayName("não altera parâmetros de providers que não são OpenRouter")
    void naoInjetaUsoEmOutroProvider() {
        var config = LLMProviderConfig.builder(LLMProvider.OPENAI)
                .modelId("gpt-4o-mini")
                .reasoning(Map.of("effort", "low"))
                .build();

        assertThat(LLMProviderHub.parametrosDoCorpo(config))
                .containsOnlyKeys("reasoning")
                .doesNotContainKey("usage");
    }
}
