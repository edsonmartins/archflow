package br.com.archflow.langchain4j.provider;

import br.com.archflow.model.config.LLMConfigPatch;
import br.com.archflow.model.config.ResolvedLLMConfig;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DefaultLLMConfigResolver")
class DefaultLLMConfigResolverTest {

    private DefaultLLMConfigResolver resolver;

    private static ResolvedLLMConfig platform() {
        return ResolvedLLMConfig.builder()
                .provider("openai")
                .model("gpt-4o-mini")
                .temperature(0.2)
                .maxTokens(1024)
                .timeout(30_000L)
                .build();
    }

    @BeforeEach
    void setUp() {
        LLMProviderHub.reset();
        resolver = new DefaultLLMConfigResolver(LLMProviderHub.getInstance());
    }

    @AfterEach
    void tearDown() {
        LLMProviderHub.reset();
    }

    @Nested
    @DisplayName("resolve() — precedência da cadeia")
    class Resolution {

        @Test
        @DisplayName("step > agent > flow > tenant > platform")
        void precedence() {
            LLMResolutionRequest req = LLMResolutionRequest.builder(platform())
                    .tenantDefault(LLMConfigPatch.builder().model("tenant-model").temperature(0.3).build())
                    .flowPatch(LLMConfigPatch.builder().model("flow-model").maxTokens(2048).build())
                    .agentPatch(LLMConfigPatch.builder().model("agent-model").build())
                    .stepPatch(LLMConfigPatch.builder().model("step-model").build())
                    .build();

            ResolvedLLMConfig out = resolver.resolve(req);

            assertThat(out.model()).isEqualTo("step-model");   // step vence
            assertThat(out.maxTokens()).isEqualTo(2048);       // só flow definiu
            assertThat(out.temperature()).isEqualTo(0.3);      // só tenant definiu
            assertThat(out.provider()).isEqualTo("openai");    // só platform
            assertThat(out.timeout()).isEqualTo(30_000L);      // só platform
        }

        @Test
        @DisplayName("tudo vazio resolve ao default da plataforma")
        void allEmptyFallsBackToPlatform() {
            ResolvedLLMConfig out = resolver.resolve(LLMResolutionRequest.builder(platform()).build());
            assertThat(out).isEqualTo(platform());
        }

        @Test
        @DisplayName("override de maxTokens por agente não afeta os demais campos")
        void agentTokenOverride() {
            LLMResolutionRequest req = LLMResolutionRequest.builder(platform())
                    .agentPatch(LLMConfigPatch.builder().maxTokens(8192).build())
                    .build();

            ResolvedLLMConfig out = resolver.resolve(req);
            assertThat(out.maxTokens()).isEqualTo(8192);
            assertThat(out.model()).isEqualTo("gpt-4o-mini");
        }
    }

    @Nested
    @DisplayName("resolução de chave por tenant")
    class ApiKeys {

        @Test
        @DisplayName("chave do tenant tem prioridade")
        void tenantKeyWins() {
            DefaultLLMConfigResolver r = new DefaultLLMConfigResolver(
                    LLMProviderHub.getInstance(),
                    (tenant, provider) -> Optional.of("tenant-key"));

            ResolvedLLMConfig resolved = ResolvedLLMConfig.builder()
                    .provider("openai").model("gpt-4o")
                    .additionalConfig(Map.of("apiKey", "inline-key"))
                    .build();

            assertThat(r.resolveApiKey("t1", "openai", resolved)).isEqualTo("tenant-key");
        }

        @Test
        @DisplayName("sem chave do tenant cai na inline (additionalConfig.apiKey)")
        void fallsBackToInline() {
            ResolvedLLMConfig resolved = ResolvedLLMConfig.builder()
                    .provider("openai").model("gpt-4o")
                    .additionalConfig(Map.of("apiKey", "inline-key"))
                    .build();

            assertThat(resolver.resolveApiKey("t1", "openai", resolved)).isEqualTo("inline-key");
        }

        @Test
        @DisplayName("sem chave alguma retorna null")
        void noKey() {
            ResolvedLLMConfig resolved = ResolvedLLMConfig.builder()
                    .provider("ollama").model("llama3.2").build();
            assertThat(resolver.resolveApiKey(null, "ollama", resolved)).isNull();
        }
    }

    @Nested
    @DisplayName("toProviderConfig / cacheConfigId")
    class Mapping {

        @Test
        @DisplayName("mapeia provider, model, maxTokens, baseUrl; não vaza apiKey em extraParams")
        void mapsFields() {
            ResolvedLLMConfig resolved = ResolvedLLMConfig.builder()
                    .provider("openai")
                    .model("gpt-4o")
                    .temperature(0.4)
                    .maxTokens(2048)
                    .additionalConfig(Map.of("baseUrl", "https://x/v1", "apiKey", "k", "organization", "acme"))
                    .build();

            LLMProviderConfig cfg = resolver.toProviderConfig(resolved, "k");

            assertThat(cfg.getProvider()).isEqualTo(LLMProvider.OPENAI);
            assertThat(cfg.getModelId()).isEqualTo("gpt-4o");
            assertThat(cfg.getMaxTokens()).isEqualTo(2048);
            assertThat(cfg.getApiKey()).isEqualTo("k");
            assertThat(cfg.getBaseUrl()).isEqualTo("https://x/v1");
            assertThat(cfg.getExtraParams()).containsEntry("organization", "acme");
            assertThat(cfg.getExtraParams()).doesNotContainKeys("apiKey", "baseUrl");
        }

        @Test
        @DisplayName("timeout é tratado como segundos (Duration.ofSeconds)")
        void timeoutInSeconds() {
            ResolvedLLMConfig resolved = ResolvedLLMConfig.builder()
                    .provider("openai").model("gpt-4o").timeout(45).build();

            LLMProviderConfig cfg = resolver.toProviderConfig(resolved, "k");

            assertThat(cfg.getTimeout()).isEqualTo(Duration.ofSeconds(45));
        }

        @Test
        @DisplayName("configs efetivas distintas geram slots de cache distintos")
        void distinctCacheSlots() {
            ResolvedLLMConfig a = ResolvedLLMConfig.builder().provider("openai").model("gpt-4o").maxTokens(1024).build();
            ResolvedLLMConfig b = ResolvedLLMConfig.builder().provider("openai").model("gpt-4o").maxTokens(4096).build();

            assertThat(resolver.cacheConfigId("t1", a, "k"))
                    .isNotEqualTo(resolver.cacheConfigId("t1", b, "k"));
        }

        @Test
        @DisplayName("a chave entra como hash, nunca em claro, no slot de cache")
        void keyNotInClear() {
            ResolvedLLMConfig a = ResolvedLLMConfig.builder().provider("openai").model("gpt-4o").maxTokens(1024).build();
            assertThat(resolver.cacheConfigId("t1", a, "super-secret")).doesNotContain("super-secret");
        }
    }

    @Nested
    @DisplayName("resolveModel — integração com o hub")
    class ResolveModel {

        @Test
        @DisplayName("produz ChatModel e reusa do cache na 2ª chamada")
        void buildsAndCaches() {
            ResolvedLLMConfig ollamaPlatform = ResolvedLLMConfig.builder()
                    .provider("ollama")
                    .model("llama3.2")
                    .temperature(0.2)
                    .additionalConfig(Map.of("baseUrl", "http://localhost:11434"))
                    .build();

            LLMResolutionRequest req = LLMResolutionRequest.builder(ollamaPlatform).tenantId("t1").build();

            ChatModel first = resolver.resolveModel(req);
            ChatModel second = resolver.resolveModel(req);

            assertThat(first).isNotNull();
            assertThat(second).isSameAs(first);
        }

        @Test
        @DisplayName("sem provider lança IllegalStateException")
        void noProviderFails() {
            ResolvedLLMConfig noProvider = ResolvedLLMConfig.builder().model("x").build();
            LLMResolutionRequest req = LLMResolutionRequest.builder(noProvider).build();

            assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class, () -> resolver.resolveModel(req)));
        }
    }
}
