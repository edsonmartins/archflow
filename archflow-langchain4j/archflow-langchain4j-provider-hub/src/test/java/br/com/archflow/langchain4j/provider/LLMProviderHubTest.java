package br.com.archflow.langchain4j.provider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
}
