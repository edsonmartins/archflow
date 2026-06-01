package br.com.archflow.langchain4j.openrouter;

import br.com.archflow.model.config.ResolvedLLMConfig;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.engine.ExecutionKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OpenRouterChatAdapter.effectiveModel — consumes resolved LLM config")
class OpenRouterEffectiveModelTest {

    private OpenRouterChatAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new OpenRouterChatAdapter();
        adapter.configure(Map.of(
                "api.key", "static-key",
                "model.name", "openai/gpt-4o-mini",
                "base.url", "https://openrouter.ai/api/v1",
                "temperature", 0.7));
    }

    private ExecutionContext ctx() {
        return new DefaultExecutionContext(null);
    }

    @Test
    @DisplayName("null/empty context → null (use default model)")
    void noContext() {
        assertThat(adapter.effectiveModel(null)).isNull();
        assertThat(adapter.effectiveModel(ctx())).isNull();
    }

    @Test
    @DisplayName("ResolvedLLMConfig in context wins, honoring model/temperature/maxTokens")
    void resolvedConfigWins() {
        ExecutionContext c = ctx();
        c.withVariable(ExecutionKeys.LLM_RESOLVED_CONFIG, ResolvedLLMConfig.builder()
                .provider("openrouter")
                .model("anthropic/claude-3.5-sonnet")
                .temperature(0.2)
                .maxTokens(4096)
                .build());

        var eff = adapter.effectiveModel(c);

        assertThat(eff).isNotNull();
        assertThat(eff.modelName()).isEqualTo("anthropic/claude-3.5-sonnet");
        assertThat(eff.temperature()).isEqualTo(0.2);
        assertThat(eff.maxTokens()).isEqualTo(4096);
        assertThat(eff.apiKey()).isEqualTo("static-key");       // herdado do config
        assertThat(eff.baseUrl()).isEqualTo("https://openrouter.ai/api/v1");
    }

    @Test
    @DisplayName("apiKey/baseUrl from additionalConfig override the static config")
    void apiKeyFromAdditionalConfig() {
        ExecutionContext c = ctx();
        c.withVariable(ExecutionKeys.LLM_RESOLVED_CONFIG, ResolvedLLMConfig.builder()
                .provider("openrouter")
                .model("openai/gpt-4o")
                .additionalConfig(Map.of("apiKey", "tenant-key", "baseUrl", "https://proxy/v1"))
                .build());

        var eff = adapter.effectiveModel(c);

        assertThat(eff.apiKey()).isEqualTo("tenant-key");
        assertThat(eff.baseUrl()).isEqualTo("https://proxy/v1");
    }

    @Test
    @DisplayName("legacy llm.model string override still works (no maxTokens)")
    void legacyModelOverride() {
        ExecutionContext c = ctx();
        c.withVariable(ExecutionKeys.LLM_MODEL, "meta-llama/llama-3.1-70b");

        var eff = adapter.effectiveModel(c);

        assertThat(eff).isNotNull();
        assertThat(eff.modelName()).isEqualTo("meta-llama/llama-3.1-70b");
        assertThat(eff.maxTokens()).isNull();
        assertThat(eff.apiKey()).isEqualTo("static-key");
    }

    @Test
    @DisplayName("resolved config takes precedence over legacy llm.model")
    void resolvedBeatsLegacy() {
        ExecutionContext c = ctx();
        c.withVariable(ExecutionKeys.LLM_MODEL, "legacy-model");
        c.withVariable(ExecutionKeys.LLM_RESOLVED_CONFIG, ResolvedLLMConfig.builder()
                .provider("openrouter").model("resolved-model").build());

        assertThat(adapter.effectiveModel(c).modelName()).isEqualTo("resolved-model");
    }
}
