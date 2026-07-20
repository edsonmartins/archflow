package br.com.archflow.langchain4j.provider;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.azure.AzureOpenAiChatModel;
import dev.langchain4j.model.azure.AzureOpenAiStreamingChatModel;
import dev.langchain4j.model.bedrock.BedrockChatModel;
import dev.langchain4j.model.bedrock.BedrockStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import dev.langchain4j.model.huggingface.HuggingFaceChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.vertexai.gemini.VertexAiGeminiChatModel;
import dev.langchain4j.model.vertexai.gemini.VertexAiGeminiStreamingChatModel;
import dev.langchain4j.model.watsonx.WatsonxChatModel;
import dev.langchain4j.model.watsonx.WatsonxStreamingChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link LLMProviderHub}.
 */
class LLMProviderHubTest {

    private LLMProviderHub hub;

    @BeforeEach
    void setUp() {
        LLMProviderHub.reset();
        hub = LLMProviderHub.getInstance();
    }

    @AfterEach
    void tearDown() {
        LLMProviderHub.reset();
    }

    @Test
    void testSingletonInstance() {
        LLMProviderHub instance1 = LLMProviderHub.getInstance();
        LLMProviderHub instance2 = LLMProviderHub.getInstance();

        assertThat(instance1).isSameAs(instance2);
    }

    @Test
    void testRegisterAndGetConfig() {
        LLMProviderConfig config = LLMProviderConfig.builder()
                .provider(LLMProvider.OPENAI)
                .modelId("gpt-4o")
                .apiKey("test-key")
                .build();

        hub.registerConfig("test", config);

        assertThat(hub.getConfig("test")).isPresent().contains(config);
    }

    @Test
    void testRegisterConfigValidates() {
        LLMProviderConfig config = LLMProviderConfig.builder()
                .provider(LLMProvider.OPENAI)
                .modelId("gpt-4o")
                .apiKey("")
                .build();

        assertThatThrownBy(() -> hub.registerConfig("test", config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("API key is required");
    }

    @Test
    void testGetConfigIds() {
        hub.registerConfig("config1", LLMProviderConfig.builder()
                .provider(LLMProvider.OPENAI)
                .modelId("gpt-4o")
                .apiKey("key1")
                .build());
        hub.registerConfig("config2", LLMProviderConfig.builder()
                .provider(LLMProvider.ANTHROPIC)
                .modelId("claude-3-5-sonnet-20241022")
                .apiKey("key2")
                .build());

        Set<String> configIds = hub.getConfigIds();

        assertThat(configIds).containsExactlyInAnyOrder("config1", "config2");
    }

    @Test
    void testRemoveConfig() {
        hub.registerConfig("test", LLMProviderConfig.builder()
                .provider(LLMProvider.OPENAI)
                .modelId("gpt-4o")
                .apiKey("key")
                .build());

        hub.removeConfig("test");

        assertThat(hub.getConfig("test")).isEmpty();
    }

    @Test
    void testGetAvailableProviders() {
        List<LLMProvider> providers = hub.getAvailableProviders();

        assertThat(providers).isNotEmpty();
        assertThat(providers).contains(
                LLMProvider.OPENAI,
                LLMProvider.ANTHROPIC,
                LLMProvider.GEMINI,
                LLMProvider.AZURE_OPENAI,
                LLMProvider.OLLAMA
        );
    }

    @Test
    void testGetProviderInfo() {
        assertThat(hub.getProviderInfo("openai"))
                .isPresent()
                .hasValue(LLMProvider.OPENAI);

        assertThat(hub.getProviderInfo("invalid")).isEmpty();
    }

    @Test
    void testGetModelsForProvider() {
        List<LLMProvider.ModelInfo> models = hub.getModelsForProvider("openai");

        assertThat(models).isNotEmpty();
        assertThat(models).anyMatch(m -> m.id().equals("gpt-4o"));
    }

    @Test
    void testGetModelInfo() {
        assertThat(hub.getModelInfo("openai", "gpt-4o"))
                .isPresent()
                .hasValueSatisfying(m -> m.id().equals("gpt-4o"));

        assertThat(hub.getModelInfo("openai", "invalid-model")).isEmpty();
    }

    @Test
    void testActiveProviderTracking() {
        LLMProviderConfig config = LLMProviderConfig.builder()
                .provider(LLMProvider.OPENAI)
                .modelId("gpt-4o")
                .apiKey("test-key")
                .build();

        hub.registerConfig("test", config);

        // Active provider is set when getModel is called
        // We can't actually call getModel here as it would require a valid API key
        // So we test that it's empty before getModel
        assertThat(hub.getActiveProvider("test")).isEmpty();
    }

    @Test
    void testClearCache() {
        hub.registerConfig("test", LLMProviderConfig.builder()
                .provider(LLMProvider.OPENAI)
                .modelId("gpt-4o")
                .apiKey("key")
                .build());

        // Model is cached after first access
        // (We don't actually call getModel to avoid API calls)
        hub.clearCache("test");

        assertThat(hub.getCacheSize()).isZero();
    }

    @Test
    void testSwitchProvider() {
        LLMProviderConfig config1 = LLMProviderConfig.builder()
                .provider(LLMProvider.OPENAI)
                .modelId("gpt-4o")
                .apiKey("key1")
                .build();

        LLMProviderConfig config2 = LLMProviderConfig.builder()
                .provider(LLMProvider.ANTHROPIC)
                .modelId("claude-3-5-sonnet-20241022")
                .apiKey("key2")
                .build();

        hub.registerConfig("test", config1);
        assertThat(hub.getConfig("test")).isPresent().contains(config1);

        hub.switchProvider("test", config2);
        assertThat(hub.getConfig("test")).isPresent().contains(config2);
    }

    @Test
    void testLLMProviderEnum() {
        // Test provider enum properties
        assertThat(LLMProvider.OPENAI.getId()).isEqualTo("openai");
        assertThat(LLMProvider.OPENAI.getDisplayName()).isEqualTo("OpenAI");
        assertThat(LLMProvider.OPENAI.requiresApiKey()).isTrue();
        assertThat(LLMProvider.OPENAI.supportsStreaming()).isTrue();

        // Test model info
        assertThat(LLMProvider.OPENAI.getModels()).isNotEmpty();
        assertThat(LLMProvider.OPENAI.getModel("gpt-4o")).isPresent();

        // Test provider lookup
        assertThat(LLMProvider.fromId("openai"))
                .isPresent()
                .hasValue(LLMProvider.OPENAI);

        assertThat(LLMProvider.fromId("invalid")).isEmpty();
    }

    @Test
    void testAllProviderIds() {
        Set<String> providerIds = LLMProvider.allProviderIds();

        assertThat(providerIds).contains(
                "openai", "anthropic", "azure-openai", "gemini", "bedrock",
                "huggingface", "ollama", "mistral", "cohere", "deepseek",
                "tongyi", "qianfan", "hunyuan", "watsonx", "vertex-ai"
        );
    }

    @Test
    void testStreamingProviders() {
        List<LLMProvider> streamingProviders = LLMProvider.streamingProviders();

        assertThat(streamingProviders)
                .contains(LLMProvider.OPENAI, LLMProvider.ANTHROPIC, LLMProvider.GEMINI);
    }

    @Test
    void testCloudProviders() {
        List<LLMProvider> cloudProviders = LLMProvider.cloudProviders();

        assertThat(cloudProviders)
                .contains(LLMProvider.OPENAI, LLMProvider.ANTHROPIC, LLMProvider.GEMINI)
                .doesNotContain(LLMProvider.OLLAMA);
    }

    @Test
    void testLocalProviders() {
        List<LLMProvider> localProviders = LLMProvider.localProviders();

        assertThat(localProviders)
                .containsExactly(LLMProvider.OLLAMA);
    }

    @Test
    void testLLMProviderConfigBuilder() {
        LLMProviderConfig config = LLMProviderConfig.builder()
                .provider(LLMProvider.OPENAI)
                .modelId("gpt-4o")
                .apiKey("test-key")
                .temperature(0.7)
                .maxTokens(2048)
                .topP(0.9)
                .build();

        assertThat(config.getProvider()).isEqualTo(LLMProvider.OPENAI);
        assertThat(config.getModelId()).isEqualTo("gpt-4o");
        assertThat(config.getApiKey()).isEqualTo("test-key");
        assertThat(config.getTemperature()).isEqualTo(0.7);
        assertThat(config.getMaxTokens()).isEqualTo(2048);
        assertThat(config.getTopP()).isEqualTo(0.9);
    }

    @Test
    void testLLMProviderConfigValidation() {
        // Missing API key for provider that requires it
        assertThatThrownBy(() -> {
            LLMProviderConfig.builder()
                    .provider(LLMProvider.OPENAI)
                    .modelId("gpt-4o")
                    .build()
                    .validate();
        }).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("API key is required");

        // Invalid temperature
        assertThatThrownBy(() -> {
            LLMProviderConfig.builder()
                    .provider(LLMProvider.OPENAI)
                    .modelId("gpt-4o")
                    .apiKey("key")
                    .temperature(3.0)
                    .build()
                    .validate();
        }).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Temperature must be between");

        // Invalid topP
        assertThatThrownBy(() -> {
            LLMProviderConfig.builder()
                    .provider(LLMProvider.OPENAI)
                    .modelId("gpt-4o")
                    .apiKey("key")
                    .topP(1.5)
                    .build()
                    .validate();
        }).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topP must be between");
    }

    @Test
    void testLLMProviderConfigBuilderMethods() {
        LLMProviderConfig config = LLMProviderConfig.builder()
                .provider("anthropic")
                .modelId("claude-3-5-sonnet-20241022")
                .apiKey("key")
                .build();

        assertThat(config.getProvider()).isEqualTo(LLMProvider.ANTHROPIC);
    }

    @Test
    void testLLMProviderConfigExtraParams() {
        LLMProviderConfig config = LLMProviderConfig.builder()
                .provider(LLMProvider.AZURE_OPENAI)
                .modelId("gpt-4o")
                .apiKey("key")
                .extraParam("azure.deploymentId", "my-deployment")
                .build();

        assertThat(config.getExtraParam("azure.deploymentId", String.class))
                .isEqualTo("my-deployment");
    }

    @Test
    void testLLMProviderConfigToBuilder() {
        LLMProviderConfig original = LLMProviderConfig.builder()
                .provider(LLMProvider.OPENAI)
                .modelId("gpt-4o")
                .apiKey("key")
                .temperature(0.7)
                .build();

        LLMProviderConfig modified = original.toBuilder()
                .temperature(0.9)
                .build();

        assertThat(modified.getModelId()).isEqualTo(original.getModelId());
        assertThat(modified.getTemperature()).isEqualTo(0.9);
    }

    @Test
    void testProviderSwitcher() {
        LLMProviderConfig primary = LLMProviderConfig.builder()
                .provider(LLMProvider.OPENAI)
                .modelId("gpt-4o")
                .apiKey("key1")
                .build();

        LLMProviderConfig fallback = LLMProviderConfig.builder()
                .provider(LLMProvider.ANTHROPIC)
                .modelId("claude-3-5-sonnet-20241022")
                .apiKey("key2")
                .build();

        ProviderSwitcher switcher = ProviderSwitcher.builder("test-switcher")
                .primary(primary)
                .fallback(fallback)
                .build();

        assertThat(switcher.getPrimaryConfig()).isEqualTo(primary);
        assertThat(switcher.getFallbackConfig()).isPresent().contains(fallback);
        assertThat(switcher.getStats()).hasSize(2);
        assertThat(switcher.getStats().get("primary").getProviderId()).isEqualTo("openai");
        assertThat(switcher.getStats().get("fallback").getProviderId()).isEqualTo("anthropic");
    }

    @Test
    void testProviderSwitcherStats() {
        ProviderSwitcher switcher = ProviderSwitcher.builder("test")
                .primary(LLMProviderConfig.builder()
                        .provider(LLMProvider.OPENAI)
                        .modelId("gpt-4o")
                        .apiKey("key")
                        .build())
                .fallback(LLMProviderConfig.builder()
                        .provider(LLMProvider.ANTHROPIC)
                        .modelId("claude-3-5-sonnet-20241022")
                        .apiKey("key")
                        .build())
                .build();

        ProviderSwitcher.ProviderStats stats = switcher.getStats("primary");
        assertThat(stats.getProviderId()).isEqualTo("openai");
        assertThat(stats.getSuccessCount()).isZero();
        assertThat(stats.getFailureCount()).isZero();
        assertThat(stats.getSuccessRate()).isZero();
    }

    @Test
    void testProviderSwitcherRequiresPrimary() {
        assertThatThrownBy(() -> {
            ProviderSwitcher.builder("test").build();
        }).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Primary config is required");
    }

    // ========== Provider -> model mapping (no network involved) ==========

    /**
     * Builds a minimal, valid config for each provider (fake credentials; model
     * construction never performs network calls).
     */
    static LLMProviderConfig minimalConfig(LLMProvider provider) {
        LLMProviderConfig.Builder builder = LLMProviderConfig.builder(provider)
                .apiKey("test-key");
        switch (provider) {
            case AZURE_OPENAI -> builder.azure("https://my-resource.openai.azure.com", "my-deployment", "test-key");
            case BEDROCK -> builder.bedrock("us-east-1", "AKIAIOSFODNN7EXAMPLE", "secret-key");
            case WATSONX -> builder.watsonx("my-project-id", "test-key", "https://us-south.ml.cloud.ibm.com");
            case VERTEX_AI -> builder.extraParam("vertex.project", "my-project")
                    .extraParam("vertex.location", "us-central1");
            default -> { /* apiKey + default modelId is enough */ }
        }
        return builder.build();
    }

    @Nested
    @DisplayName("createModel: provider -> blocking model class")
    class BlockingModelConstruction {

        private ChatModel modelFor(LLMProvider provider) {
            hub.registerConfig("m:" + provider.getId(), minimalConfig(provider));
            return hub.getModel("m:" + provider.getId(), false);
        }

        @Test
        void openAi() {
            assertThat(modelFor(LLMProvider.OPENAI)).isInstanceOf(OpenAiChatModel.class);
        }

        @Test
        void anthropic() {
            assertThat(modelFor(LLMProvider.ANTHROPIC)).isInstanceOf(AnthropicChatModel.class);
        }

        @Test
        void azureOpenAi() {
            assertThat(modelFor(LLMProvider.AZURE_OPENAI)).isInstanceOf(AzureOpenAiChatModel.class);
        }

        @Test
        void gemini() {
            assertThat(modelFor(LLMProvider.GEMINI)).isInstanceOf(GoogleAiGeminiChatModel.class);
        }

        @Test
        void ollama() {
            assertThat(modelFor(LLMProvider.OLLAMA)).isInstanceOf(OllamaChatModel.class);
        }

        @Test
        void bedrock() {
            assertThat(modelFor(LLMProvider.BEDROCK)).isInstanceOf(BedrockChatModel.class);
        }

        @Test
        void huggingFace() {
            assertThat(modelFor(LLMProvider.HUGGINGFACE)).isInstanceOf(HuggingFaceChatModel.class);
        }

        @Test
        void watsonx() {
            assertThat(modelFor(LLMProvider.WATSONX)).isInstanceOf(WatsonxChatModel.class);
        }

        @Test
        void vertexAi() {
            assertThat(modelFor(LLMProvider.VERTEX_AI)).isInstanceOf(VertexAiGeminiChatModel.class);
        }

        @Test
        void openAiCompatibleProvidersUseOpenAiClient() {
            for (LLMProvider provider : List.of(
                    LLMProvider.DEEPSEEK, LLMProvider.TONGYI, LLMProvider.MISTRAL,
                    LLMProvider.COHERE, LLMProvider.QIANFAN, LLMProvider.HUNYUAN,
                    LLMProvider.OPENROUTER)) {
                assertThat(modelFor(provider))
                        .as("provider %s", provider)
                        .isInstanceOf(OpenAiChatModel.class);
            }
        }

        @Test
        void watsonxWithoutProjectIdFailsWithHonestMessage() {
            hub.registerConfig("wx-bad", LLMProviderConfig.builder(LLMProvider.WATSONX)
                    .apiKey("key")
                    .build());

            assertThatThrownBy(() -> hub.getModel("wx-bad", false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("watsonx.projectId");
        }

        @Test
        void vertexAiWithoutProjectFailsWithHonestMessage() {
            hub.registerConfig("vx-bad", LLMProviderConfig.builder(LLMProvider.VERTEX_AI)
                    .build());

            assertThatThrownBy(() -> hub.getModel("vx-bad", false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("vertex.project");
        }
    }

    @Nested
    @DisplayName("createStreamingModel: provider -> streaming model class")
    class StreamingModelConstruction {

        private StreamingChatModel streamingFor(LLMProvider provider) {
            hub.registerConfig("s:" + provider.getId(), minimalConfig(provider));
            return hub.getStreamingModel("s:" + provider.getId(), false);
        }

        @Test
        void openAi() {
            assertThat(streamingFor(LLMProvider.OPENAI)).isInstanceOf(OpenAiStreamingChatModel.class);
        }

        @Test
        void anthropic() {
            assertThat(streamingFor(LLMProvider.ANTHROPIC)).isInstanceOf(AnthropicStreamingChatModel.class);
        }

        @Test
        void azureOpenAi() {
            assertThat(streamingFor(LLMProvider.AZURE_OPENAI)).isInstanceOf(AzureOpenAiStreamingChatModel.class);
        }

        @Test
        void gemini() {
            assertThat(streamingFor(LLMProvider.GEMINI)).isInstanceOf(GoogleAiGeminiStreamingChatModel.class);
        }

        @Test
        void ollama() {
            assertThat(streamingFor(LLMProvider.OLLAMA)).isInstanceOf(OllamaStreamingChatModel.class);
        }

        @Test
        void bedrock() {
            assertThat(streamingFor(LLMProvider.BEDROCK)).isInstanceOf(BedrockStreamingChatModel.class);
        }

        @Test
        void watsonx() {
            assertThat(streamingFor(LLMProvider.WATSONX)).isInstanceOf(WatsonxStreamingChatModel.class);
        }

        @Test
        void vertexAi() {
            assertThat(streamingFor(LLMProvider.VERTEX_AI)).isInstanceOf(VertexAiGeminiStreamingChatModel.class);
        }

        @Test
        void openAiCompatibleProvidersUseOpenAiStreamingClient() {
            for (LLMProvider provider : List.of(
                    LLMProvider.DEEPSEEK, LLMProvider.TONGYI, LLMProvider.MISTRAL,
                    LLMProvider.COHERE, LLMProvider.QIANFAN, LLMProvider.HUNYUAN,
                    LLMProvider.OPENROUTER)) {
                assertThat(streamingFor(provider))
                        .as("provider %s", provider)
                        .isInstanceOf(OpenAiStreamingChatModel.class);
            }
        }

        @Test
        void huggingFaceDoesNotSupportStreaming() {
            hub.registerConfig("s:hf", minimalConfig(LLMProvider.HUGGINGFACE));

            assertThatThrownBy(() -> hub.getStreamingModel("s:hf", false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not support streaming");
        }

        @Test
        void supportsStreamingFlagMatchesImplementation() {
            for (LLMProvider provider : LLMProvider.values()) {
                hub.registerConfig("flag:" + provider.getId(), minimalConfig(provider));
                if (provider.supportsStreaming()) {
                    assertThat(hub.getStreamingModel("flag:" + provider.getId(), false))
                            .as("provider %s declares streaming support", provider)
                            .isNotNull();
                } else {
                    assertThatThrownBy(() -> hub.getStreamingModel("flag:" + provider.getId(), false))
                            .as("provider %s declares no streaming support", provider)
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("does not support streaming");
                }
            }
        }
    }

    @Nested
    @DisplayName("OpenAI-compatible default endpoints")
    class OpenAiCompatibleEndpoints {

        @Test
        void compatibilityEndpointsAreTheRealOnes() {
            assertThat(LLMProvider.COHERE.getBaseUrl())
                    .isEqualTo("https://api.cohere.ai/compatibility/v1");
            assertThat(LLMProvider.QIANFAN.getBaseUrl())
                    .isEqualTo("https://qianfan.baidubce.com/v2");
            assertThat(LLMProvider.HUNYUAN.getBaseUrl())
                    .isEqualTo("https://api.hunyuan.cloud.tencent.com/v1");
            assertThat(LLMProvider.DEEPSEEK.getBaseUrl())
                    .isEqualTo("https://api.deepseek.com/v1");
            assertThat(LLMProvider.TONGYI.getBaseUrl())
                    .isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
            assertThat(LLMProvider.MISTRAL.getBaseUrl())
                    .isEqualTo("https://api.mistral.ai/v1");
            assertThat(LLMProvider.OPENROUTER.getBaseUrl())
                    .isEqualTo("https://openrouter.ai/api/v1");
        }

        @Test
        void blockingAndStreamingShareTheSameDefaultBaseUrl() {
            // Both paths resolve the endpoint through the same helper, so the
            // streaming default can never drift from the blocking default.
            for (LLMProvider provider : List.of(
                    LLMProvider.DEEPSEEK, LLMProvider.TONGYI, LLMProvider.MISTRAL,
                    LLMProvider.COHERE, LLMProvider.QIANFAN, LLMProvider.HUNYUAN,
                    LLMProvider.OPENROUTER)) {
                LLMProviderConfig config = minimalConfig(provider);
                assertThat(LLMProviderHub.effectiveBaseUrl(config))
                        .as("default baseUrl for %s", provider)
                        .isEqualTo(provider.getBaseUrl());
            }
        }

        @Test
        void explicitBaseUrlOverridesDefault() {
            LLMProviderConfig config = LLMProviderConfig.builder(LLMProvider.COHERE)
                    .apiKey("key")
                    .baseUrl("https://proxy.example.com/v1")
                    .build();

            assertThat(LLMProviderHub.effectiveBaseUrl(config))
                    .isEqualTo("https://proxy.example.com/v1");
        }
    }

    @Nested
    @DisplayName("withProvider: thread-safe temporary override")
    class WithProviderTests {

        private LLMProviderConfig openAiConfig() {
            return LLMProviderConfig.builder(LLMProvider.OPENAI)
                    .modelId("gpt-4o")
                    .apiKey("openai-key")
                    .build();
        }

        private LLMProviderConfig anthropicConfig() {
            return LLMProviderConfig.builder(LLMProvider.ANTHROPIC)
                    .modelId("claude-3-5-sonnet-20241022")
                    .apiKey("anthropic-key")
                    .build();
        }

        @Test
        void operationSeesTemporaryProvider() {
            hub.registerConfig("ctx", openAiConfig());

            ChatModel duringOverride = hub.withProvider("ctx", anthropicConfig(),
                    () -> hub.getModel("ctx", false));

            assertThat(duringOverride).isInstanceOf(AnthropicChatModel.class);
            assertThat(hub.getModel("ctx", false)).isInstanceOf(OpenAiChatModel.class);
        }

        @Test
        void sharedConfigIsNeverMutated() {
            LLMProviderConfig original = openAiConfig();
            hub.registerConfig("ctx", original);

            hub.withProvider("ctx", anthropicConfig(), () -> {
                assertThat(hub.getConfig("ctx")).contains(anthropicConfig());
                return null;
            });

            assertThat(hub.getConfig("ctx")).contains(original);
        }

        @Test
        void otherThreadsAreUnaffectedDuringOverride() throws Exception {
            hub.registerConfig("ctx", openAiConfig());

            AtomicReference<LLMProvider> seenByOtherThread = new AtomicReference<>();
            AtomicReference<Class<?>> modelSeenByOtherThread = new AtomicReference<>();

            hub.withProvider("ctx", anthropicConfig(), () -> {
                Thread other = new Thread(() -> {
                    seenByOtherThread.set(hub.getConfig("ctx").orElseThrow().getProvider());
                    modelSeenByOtherThread.set(hub.getModel("ctx", false).getClass());
                });
                other.start();
                try {
                    other.join(30_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                return null;
            });

            assertThat(seenByOtherThread.get()).isEqualTo(LLMProvider.OPENAI);
            assertThat(modelSeenByOtherThread.get()).isEqualTo(OpenAiChatModel.class);
        }

        @Test
        void overrideIsRemovedEvenWhenOperationThrows() {
            hub.registerConfig("ctx", openAiConfig());

            assertThatThrownBy(() -> hub.withProvider("ctx", anthropicConfig(), () -> {
                throw new IllegalStateException("boom");
            })).isInstanceOf(IllegalStateException.class).hasMessage("boom");

            assertThat(hub.getConfig("ctx")).contains(openAiConfig());
            assertThat(hub.getModel("ctx", false)).isInstanceOf(OpenAiChatModel.class);
        }

        @Test
        void sharedModelCacheIsNotPolluted() {
            hub.registerConfig("ctx", openAiConfig());
            ChatModel cached = hub.getModel("ctx");
            assertThat(hub.getCacheSize()).isEqualTo(1);

            hub.withProvider("ctx", anthropicConfig(), () -> hub.getModel("ctx"));

            assertThat(hub.getCacheSize()).isEqualTo(1);
            assertThat(hub.getModel("ctx")).isSameAs(cached);
        }

        @Test
        void invalidTemporaryConfigIsRejected() {
            hub.registerConfig("ctx", openAiConfig());

            LLMProviderConfig invalid = LLMProviderConfig.builder(LLMProvider.ANTHROPIC)
                    .modelId("claude-3-5-sonnet-20241022")
                    .build(); // missing API key

            assertThatThrownBy(() -> hub.withProvider("ctx", invalid, () -> "unreachable"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("API key is required");
        }
    }
}
