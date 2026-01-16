package br.com.archflow.langchain4j.provider;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.azure.AzureOpenAiChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Central hub for unified access to 15+ LLM providers with runtime switching.
 *
 * <p>The ProviderHub manages:
 * <ul>
 *   <li><b>Model creation:</b> Creates ChatModel instances from config</li>
 *   <li><b>Runtime switching:</b> Switch providers at runtime without code changes</li>
 *   <li><b>Model caching:</b> Reuses existing model instances</li>
 *   <li><b>Provider discovery:</b> Lists all available providers and models</li>
 *   <li><b>Active provider tracking:</b> Tracks currently active provider per context</li>
 * </ul>
 *
 * <p>Usage example:
 * <pre>{@code
 * // Configure the hub
 * LLMProviderHub hub = LLMProviderHub.getInstance();
 * hub.registerConfig("default", LLMProviderConfig.builder()
 *     .provider(LLMProvider.OPENAI)
 *     .apiKey("sk-...")
 *     .modelId("gpt-4o")
 *     .build());
 *
 * // Get a model
 * ChatModel model = hub.getModel("default");
 * String response = model.generate("Hello!");
 *
 * // Switch provider at runtime
 * hub.registerConfig("default", LLMProviderConfig.builder()
 *     .provider(LLMProvider.ANTHROPIC)
 *     .apiKey("sk-ant-...")
 *     .modelId("claude-3-5-sonnet-20241022")
 *     .build());
 * model = hub.getModel("default");  // Now uses Claude
 * }</pre>
 */
public class LLMProviderHub {

    private static final Logger log = LoggerFactory.getLogger(LLMProviderHub.class);

    private static volatile LLMProviderHub instance;

    private final Map<String, LLMProviderConfig> configs;
    private final Map<String, CachedModel> modelCache;
    private final Map<String, String> activeProviders; // context -> providerId
    private final Map<String, ProviderSwitchListener> switchListeners;

    private LLMProviderHub() {
        this.configs = new ConcurrentHashMap<>();
        this.modelCache = new ConcurrentHashMap<>();
        this.activeProviders = new ConcurrentHashMap<>();
        this.switchListeners = new ConcurrentHashMap<>();
    }

    /**
     * Gets the singleton instance of the ProviderHub.
     */
    public static LLMProviderHub getInstance() {
        if (instance == null) {
            synchronized (LLMProviderHub.class) {
                if (instance == null) {
                    instance = new LLMProviderHub();
                }
            }
        }
        return instance;
    }

    /**
     * Resets the singleton instance (mainly for testing).
     */
    public static void reset() {
        synchronized (LLMProviderHub.class) {
            if (instance != null) {
                instance.shutdown();
            }
            instance = null;
        }
    }

    // ========== Configuration ==========

    /**
     * Registers a configuration with a given ID.
     *
     * @param configId The unique identifier for this configuration
     * @param config The configuration to register
     */
    public void registerConfig(String configId, LLMProviderConfig config) {
        config.validate();

        LLMProviderConfig oldConfig = configs.put(configId, config);
        String oldProviderId = oldConfig != null ? oldConfig.getProvider().getId() : null;
        String newProviderId = config.getProvider().getId();

        // Clear cached model for this config if provider changed
        if (!Objects.equals(oldProviderId, newProviderId)) {
            modelCache.remove(configId);
            logProviderSwitch(configId, oldProviderId, newProviderId);
        }

        log.info("Registered config '{}' for provider '{}', model '{}'",
                configId, config.getProvider().getDisplayName(), config.getModelId());
    }

    /**
     * Gets a registered configuration.
     */
    public Optional<LLMProviderConfig> getConfig(String configId) {
        return Optional.ofNullable(configs.get(configId));
    }

    /**
     * Removes a configuration.
     */
    public void removeConfig(String configId) {
        LLMProviderConfig removed = configs.remove(configId);
        if (removed != null) {
            modelCache.remove(configId);
            activeProviders.remove(configId);
            log.info("Removed config '{}'", configId);
        }
    }

    /**
     * Lists all registered configuration IDs.
     */
    public Set<String> getConfigIds() {
        return Set.copyOf(configs.keySet());
    }

    // ========== Model Creation ==========

    /**
     * Gets a ChatModel for the given config ID.
     *
     * @param configId The configuration ID
     * @return A ChatModel instance
     * @throws IllegalArgumentException if config ID is not registered
     */
    public ChatModel getModel(String configId) {
        return getModel(configId, true);
    }

    /**
     * Gets a ChatModel for the given config ID.
     *
     * @param configId The configuration ID
     * @param useCache Whether to use cached model instance
     * @return A ChatModel instance
     * @throws IllegalArgumentException if config ID is not registered
     */
    public ChatModel getModel(String configId, boolean useCache) {
        LLMProviderConfig config = getConfig(configId)
                .orElseThrow(() -> new IllegalArgumentException("Config not registered: " + configId));

        if (useCache) {
            CachedModel cached = modelCache.get(configId);
            if (cached != null && cached.config().equals(config)) {
                return (ChatModel) cached.model();
            }
        }

        ChatModel model = createModel(config);
        activeProviders.put(configId, config.getProvider().getId());

        if (useCache) {
            modelCache.put(configId, new CachedModel(model, config));
        }

        return model;
    }

    /**
     * Gets a StreamingChatModel for the given config ID.
     *
     * @param configId The configuration ID
     * @return A StreamingChatModel instance
     * @throws IllegalArgumentException if config ID is not registered or provider doesn't support streaming
     */
    public StreamingChatModel getStreamingModel(String configId) {
        return getStreamingModel(configId, true);
    }

    /**
     * Gets a StreamingChatModel for the given config ID.
     *
     * @param configId The configuration ID
     * @param useCache Whether to use cached model instance
     * @return A StreamingChatModel instance
     * @throws IllegalArgumentException if config ID is not registered or provider doesn't support streaming
     */
    @SuppressWarnings("unchecked")
    public StreamingChatModel getStreamingModel(String configId, boolean useCache) {
        LLMProviderConfig config = getConfig(configId)
                .orElseThrow(() -> new IllegalArgumentException("Config not registered: " + configId));

        if (!config.getProvider().supportsStreaming()) {
            throw new IllegalArgumentException(
                    "Provider " + config.getProvider().getDisplayName() + " does not support streaming");
        }

        String cacheKey = configId + ":streaming";
        if (useCache) {
            CachedModel cached = modelCache.get(cacheKey);
            if (cached != null && cached.config().equals(config)) {
                return (StreamingChatModel) cached.model();
            }
        }

        StreamingChatModel model = createStreamingModel(config);
        activeProviders.put(configId, config.getProvider().getId());

        if (useCache) {
            modelCache.put(cacheKey, new CachedModel(model, config));
        }

        return model;
    }

    /**
     * Creates a new ChatModel from the given configuration.
     */
    private ChatModel createModel(LLMProviderConfig config) {
        return switch (config.getProvider()) {
            case OPENAI -> createOpenAiModel(config);
            case ANTHROPIC -> createAnthropicModel(config);
            case AZURE_OPENAI -> createAzureOpenAiModel(config);
            case GEMINI -> createGeminiModel(config);
            case OLLAMA -> createOllamaModel(config);
            case DEEPSEEK -> createDeepSeekModel(config);
            case TONGYI -> createTongyiModel(config);
            default -> throw new UnsupportedOperationException(
                    "Provider " + config.getProvider().getDisplayName() + " requires additional dependencies. " +
                    "Add the appropriate langchain4j dependency to your project.");
        };
    }

    /**
     * Creates a new StreamingChatModel from the given configuration.
     */
    @SuppressWarnings("unchecked")
    private StreamingChatModel createStreamingModel(LLMProviderConfig config) {
        return switch (config.getProvider()) {
            case OPENAI -> (StreamingChatModel) createOpenAiModel(config);
            case ANTHROPIC -> (StreamingChatModel) createAnthropicModel(config);
            case AZURE_OPENAI -> (StreamingChatModel) createAzureOpenAiModel(config);
            case GEMINI -> (StreamingChatModel) createGeminiModel(config);
            case OLLAMA -> (StreamingChatModel) createOllamaModel(config);
            default -> throw new IllegalArgumentException(
                    "Streaming not yet supported for " + config.getProvider().getDisplayName());
        };
    }

    // ========== Provider-Specific Model Creation ==========

    private ChatModel createOpenAiModel(LLMProviderConfig config) {
        var builder = OpenAiChatModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getModelId())
                .temperature(config.getTemperature())
                .timeout(config.getTimeout());

        if (config.getTopP() != null) {
            builder.topP(config.getTopP());
        }
        if (config.getMaxTokens() != null) {
            builder.maxTokens(config.getMaxTokens());
        }
        if (config.getBaseUrl() != null) {
            builder.baseUrl(config.getBaseUrl());
        }

        return builder.build();
    }

    private ChatModel createAnthropicModel(LLMProviderConfig config) {
        var builder = AnthropicChatModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getModelId())
                .temperature(config.getTemperature())
                .timeout(config.getTimeout());

        if (config.getTopP() != null) {
            builder.topP(config.getTopP());
        }
        if (config.getMaxTokens() != null) {
            builder.maxTokens(config.getMaxTokens());
        }
        if (config.getBaseUrl() != null) {
            builder.baseUrl(config.getBaseUrl());
        }

        return builder.build();
    }

    private ChatModel createAzureOpenAiModel(LLMProviderConfig config) {
        String deploymentId = config.getExtraParam("azure.deploymentId", String.class,
                config.getModelId());

        return AzureOpenAiChatModel.builder()
                .endpoint(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .deploymentName(deploymentId)
                .temperature(config.getTemperature())
                .timeout(config.getTimeout())
                .build();
    }

    private ChatModel createGeminiModel(LLMProviderConfig config) {
        // Dynamically create Gemini model using reflection since we might not have the dependency
        try {
            // Check if the Gemini classes are available
            Class<?> geminiClass = Class.forName("dev.langchain4j.model.google.gemini.GeminiChatModel");
            Class<?> builderClass = Class.forName("dev.langchain4j.model.google.gemini.GeminiChatModel$GeminiChatModelBuilder");

            Object builder = geminiClass.getMethod("builder").invoke(null);

            // Set properties using reflection
            builder.getClass().getMethod("apiKey", String.class).invoke(builder, config.getApiKey());
            builder.getClass().getMethod("modelName", String.class).invoke(builder, config.getModelId());
            builder.getClass().getMethod("temperature", Double.class).invoke(builder, config.getTemperature());
            builder.getClass().getMethod("timeout", Duration.class).invoke(builder, config.getTimeout());

            return (ChatModel) builder.getClass().getMethod("build").invoke(builder);
        } catch (Exception e) {
            throw new UnsupportedOperationException(
                    "Gemini provider requires langchain4j-google-ai-gemini dependency", e);
        }
    }

    private ChatModel createOllamaModel(LLMProviderConfig config) {
        try {
            Class<?> ollamaClass = Class.forName("dev.langchain4j.model.ollama.OllamaChatModel");
            Class<?> builderClass = Class.forName("dev.langchain4j.model.ollama.OllamaChatModel$OllamaChatModelBuilder");

            Object builder = ollamaClass.getMethod("builder").invoke(null);

            String baseUrl = config.getBaseUrl() != null ? config.getBaseUrl() : "http://localhost:11434";

            builder.getClass().getMethod("baseUrl", String.class).invoke(builder, baseUrl);
            builder.getClass().getMethod("modelName", String.class).invoke(builder, config.getModelId());
            builder.getClass().getMethod("temperature", Double.class).invoke(builder, config.getTemperature());
            builder.getClass().getMethod("timeout", Duration.class).invoke(builder, config.getTimeout());

            return (ChatModel) builder.getClass().getMethod("build").invoke(builder);
        } catch (Exception e) {
            throw new UnsupportedOperationException(
                    "Ollama provider requires langchain4j-ollama dependency", e);
        }
    }

    private ChatModel createDeepSeekModel(LLMProviderConfig config) {
        // DeepSeek uses OpenAI-compatible API
        String baseUrl = config.getBaseUrl() != null ? config.getBaseUrl() : "https://api.deepseek.com";

        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(config.getApiKey())
                .modelName(config.getModelId())
                .temperature(config.getTemperature())
                .timeout(config.getTimeout())
                .build();
    }

    private ChatModel createTongyiModel(LLMProviderConfig config) {
        // Tongyi uses OpenAI-compatible API
        String baseUrl = config.getBaseUrl() != null
                ? config.getBaseUrl()
                : "https://dashscope.aliyuncs.com/compatible-mode/v1";

        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(config.getApiKey())
                .modelName(config.getModelId())
                .temperature(config.getTemperature())
                .timeout(config.getTimeout())
                .build();
    }

    // ========== Runtime Switching ==========

    /**
     * Switches the provider for a given config ID at runtime.
     *
     * <p>This is the primary method for runtime provider switching.
     * It updates the configuration and invalidates cached models.
     *
     * @param configId The configuration ID to switch
     * @param newConfig The new configuration
     */
    public void switchProvider(String configId, LLMProviderConfig newConfig) {
        String oldProviderId = activeProviders.get(configId);
        String newProviderId = newConfig.getProvider().getId();

        registerConfig(configId, newConfig);
        notifySwitchListeners(configId, oldProviderId, newProviderId);
    }

    /**
     * Executes an operation with a temporary provider override.
     *
     * @param configId The base configuration ID
     * @param tempConfig Temporary configuration to use
     * @param operation The operation to execute
     * @param <T> The result type
     * @return The result of the operation
     */
    public <T> T withProvider(String configId, LLMProviderConfig tempConfig, Supplier<T> operation) {
        String cacheKey = configId + ":temp";
        try {
            // Store original config
            LLMProviderConfig original = configs.get(configId);
            if (original != null) {
                configs.put(cacheKey, original);
            }

            // Apply temporary config
            registerConfig(configId, tempConfig);

            // Execute operation
            return operation.get();

        } finally {
            // Restore original config
            LLMProviderConfig original = configs.remove(cacheKey);
            if (original != null) {
                registerConfig(configId, original);
            }
            // Clear temp cache
            modelCache.remove(configId);
        }
    }

    /**
     * Adds a listener for provider switch events.
     */
    public void addSwitchListener(String listenerId, ProviderSwitchListener listener) {
        switchListeners.put(listenerId, listener);
    }

    /**
     * Removes a provider switch listener.
     */
    public void removeSwitchListener(String listenerId) {
        switchListeners.remove(listenerId);
    }

    private void logProviderSwitch(String configId, String oldProvider, String newProvider) {
        log.info("Provider switch for config '{}': {} -> {}",
                configId, oldProvider, newProvider);
    }

    private void notifySwitchListeners(String configId, String oldProvider, String newProvider) {
        for (ProviderSwitchListener listener : switchListeners.values()) {
            try {
                listener.onProviderSwitch(configId, oldProvider, newProvider);
            } catch (Exception e) {
                log.error("Error notifying switch listener", e);
            }
        }
    }

    /**
     * Gets the currently active provider for a config ID.
     */
    public Optional<String> getActiveProvider(String configId) {
        return Optional.ofNullable(activeProviders.get(configId));
    }

    // ========== Discovery ==========

    /**
     * Lists all available providers.
     */
    public List<LLMProvider> getAvailableProviders() {
        return List.of(LLMProvider.values());
    }

    /**
     * Gets information about a specific provider.
     */
    public Optional<LLMProvider> getProviderInfo(String providerId) {
        return LLMProvider.fromId(providerId);
    }

    /**
     * Gets all available models for a provider.
     */
    public List<LLMProvider.ModelInfo> getModelsForProvider(String providerId) {
        return LLMProvider.fromId(providerId)
                .map(LLMProvider::getModels)
                .orElse(List.of());
    }

    /**
     * Gets model info for a specific model.
     */
    public Optional<LLMProvider.ModelInfo> getModelInfo(String providerId, String modelId) {
        return LLMProvider.fromId(providerId)
                .flatMap(provider -> provider.getModel(modelId));
    }

    // ========== Cache Management ==========

    /**
     * Clears the model cache for a specific config.
     */
    public void clearCache(String configId) {
        modelCache.remove(configId);
        modelCache.remove(configId + ":streaming");
    }

    /**
     * Clears all cached models.
     */
    public void clearAllCache() {
        modelCache.clear();
    }

    /**
     * Gets the number of cached models.
     */
    public int getCacheSize() {
        return modelCache.size();
    }

    // ========== Lifecycle ==========

    /**
     * Shuts down the hub and clears all resources.
     */
    public void shutdown() {
        log.info("Shutting down LLMProviderHub");
        configs.clear();
        modelCache.clear();
        activeProviders.clear();
        switchListeners.clear();
    }

    // ========== Inner Classes ==========

    /**
     * A cached model with its configuration.
     */
    private record CachedModel(
            Object model,
            LLMProviderConfig config
    ) {}

    /**
     * Listener for provider switch events.
     */
    @FunctionalInterface
    public interface ProviderSwitchListener {
        void onProviderSwitch(String configId, String oldProviderId, String newProviderId);
    }
}
