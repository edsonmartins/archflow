package br.com.archflow.model.config;

import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.model.flow.StepType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LLMConfigPatch + herança de configuração")
class LLMConfigPatchTest {

    private static ResolvedLLMConfig platform() {
        return ResolvedLLMConfig.builder()
                .provider("openai")
                .model("gpt-4o-mini")
                .temperature(0.2)
                .maxTokens(1024)
                .timeout(30_000L)
                .additionalConfig(Map.of("baseUrl", "https://api.openai.com/v1"))
                .build();
    }

    @Nested
    @DisplayName("empty / isEmpty")
    class EmptyPatch {

        @Test
        @DisplayName("empty() não sobrescreve nada")
        void emptyKeepsParent() {
            ResolvedLLMConfig parent = platform();
            ResolvedLLMConfig out = LLMConfigPatch.empty().applyOver(parent);

            assertThat(LLMConfigPatch.empty().isEmpty()).isTrue();
            assertThat(out).isEqualTo(parent);
        }
    }

    @Nested
    @DisplayName("applyOver")
    class ApplyOver {

        @Test
        @DisplayName("override total: campos presentes vencem o pai")
        void fullOverride() {
            ResolvedLLMConfig out = LLMConfigPatch.builder()
                    .provider("anthropic")
                    .model("claude-3-5-sonnet-20241022")
                    .temperature(0.9)
                    .maxTokens(4096)
                    .timeout(60_000L)
                    .build()
                    .applyOver(platform());

            assertThat(out.provider()).isEqualTo("anthropic");
            assertThat(out.model()).isEqualTo("claude-3-5-sonnet-20241022");
            assertThat(out.temperature()).isEqualTo(0.9);
            assertThat(out.maxTokens()).isEqualTo(4096);
            assertThat(out.timeout()).isEqualTo(60_000L);
        }

        @Test
        @DisplayName("override parcial (só maxTokens) preserva o resto do pai")
        void partialOverrideKeepsParent() {
            ResolvedLLMConfig out = LLMConfigPatch.builder()
                    .maxTokens(8192)
                    .build()
                    .applyOver(platform());

            assertThat(out.maxTokens()).isEqualTo(8192);          // sobrescrito
            assertThat(out.provider()).isEqualTo("openai");       // herdado
            assertThat(out.model()).isEqualTo("gpt-4o-mini");     // herdado
            assertThat(out.temperature()).isEqualTo(0.2);         // herdado
            assertThat(out.timeout()).isEqualTo(30_000L);         // herdado
        }

        @Test
        @DisplayName("additionalConfig faz merge raso (patch vence por chave)")
        void additionalConfigShallowMerge() {
            ResolvedLLMConfig out = LLMConfigPatch.builder()
                    .additional("baseUrl", "https://openrouter.ai/api/v1")
                    .additional("organization", "acme")
                    .build()
                    .applyOver(platform());

            assertThat(out.additionalConfig())
                    .containsEntry("baseUrl", "https://openrouter.ai/api/v1") // sobrescrito
                    .containsEntry("organization", "acme");                   // adicionado
        }
    }

    @Nested
    @DisplayName("defaults em FlowConfiguration / FlowStep")
    class Defaults {

        @Test
        @DisplayName("FlowConfiguration.getLLMPatch deriva de getLLMConfig")
        void flowConfigDerivesPatch() {
            FlowConfiguration cfg = new FlowConfiguration() {
                @Override public long getTimeout() { return 0; }
                @Override public RetryPolicy getRetryPolicy() { return null; }
                @Override public LLMConfig getLLMConfig() {
                    return new LLMConfig("gpt-4o", 0.5, 2048, 15_000L, Map.of("top_p", 0.9));
                }
                @Override public MonitoringConfig getMonitoringConfig() { return null; }
            };

            LLMConfigPatch patch = cfg.getLLMPatch();
            assertThat(patch.model()).contains("gpt-4o");
            assertThat(patch.maxTokens().getAsInt()).isEqualTo(2048);
            assertThat(patch.temperature().getAsDouble()).isEqualTo(0.5);
            assertThat(patch.additionalConfig()).containsEntry("top_p", 0.9);
        }

        @Test
        @DisplayName("FlowConfiguration.getLLMPatch é vazio quando não há LLMConfig")
        void flowConfigEmptyWhenNoLlm() {
            FlowConfiguration cfg = new FlowConfiguration() {
                @Override public long getTimeout() { return 0; }
                @Override public RetryPolicy getRetryPolicy() { return null; }
                @Override public LLMConfig getLLMConfig() { return null; }
                @Override public MonitoringConfig getMonitoringConfig() { return null; }
            };
            assertThat(cfg.getLLMPatch().isEmpty()).isTrue();
        }

        @Test
        @DisplayName("FlowStep.getLLMPatch é vazio por padrão")
        void flowStepEmptyByDefault() {
            FlowStep step = new FlowStep() {
                @Override public String getId() { return "s1"; }
                @Override public StepType getType() { return null; }
                @Override public List<StepConnection> getConnections() { return List.of(); }
                @Override public CompletableFuture<StepResult> execute(ExecutionContext context) {
                    return CompletableFuture.completedFuture(null);
                }
            };
            assertThat(step.getLLMPatch().isEmpty()).isTrue();
        }
    }
}
