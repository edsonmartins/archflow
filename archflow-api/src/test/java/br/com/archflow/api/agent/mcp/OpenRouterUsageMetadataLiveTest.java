package br.com.archflow.api.agent.mcp;

import br.com.archflow.api.agent.vendax.VendaxResult;
import br.com.archflow.langchain4j.provider.LLMProvider;
import br.com.archflow.langchain4j.provider.LLMProviderConfig;
import br.com.archflow.langchain4j.provider.LLMProviderHub;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicitly opt-in paid smoke test; never runs in ordinary builds. */
class OpenRouterUsageMetadataLiveTest {

    @Test
    @EnabledIfSystemProperty(named = "openrouter.live", matches = "true")
    @EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")
    @DisplayName("[live opt-in] captures actual OpenRouter model, endpoint and cost")
    void capturesProviderResponseMetadata() {
        LLMProviderHub.reset();
        LLMProviderHub hub = LLMProviderHub.getInstance();
        hub.registerConfig("openrouter-live-usage", LLMProviderConfig.builder(LLMProvider.OPENROUTER)
                .apiKey(System.getenv("OPENROUTER_API_KEY"))
                .modelId("google/gemini-2.5-flash-lite")
                .maxTokens(24)
                .build());

        var response = hub.getModel("openrouter-live-usage", false).chat(ChatRequest.builder()
                .messages(UserMessage.from("Reply with exactly: OK"))
                .build());
        var counter = new ContadorDeUso();
        var options = new McpAgentRunner.Options(null, null, null, 1, null, null,
                null, null, Set.of(), counter);
        McpAgentRunner.somarUso(options, "openrouter", "openrouter/google/gemini-2.5-flash-lite", response);
        var summary = counter.resumo().orElseThrow();
        var callbackUsage = VendaxResult.Uso.de(summary, null);
        var turn = callbackUsage.modelUsage().getFirst();
        var tokens = response.tokenUsage();

        assertThat(turn.effectiveModel()).isNotBlank();
        assertThat(turn.effectiveProvider()).isNotBlank();
        assertThat(turn.declaredCostUsd()).isNotNull().isFinite().isGreaterThanOrEqualTo(0.0);
        System.out.printf("LIVE_OPENROUTER_USAGE requestedModel=google/gemini-2.5-flash-lite effectiveModel=%s "
                        + "effectiveProvider=%s inputTokens=%s outputTokens=%s declaredCostUsd=%s%n",
                turn.effectiveModel(), turn.effectiveProvider(), tokens == null ? null : tokens.inputTokenCount(),
                tokens == null ? null : tokens.outputTokenCount(), turn.declaredCostUsd());
    }
}
