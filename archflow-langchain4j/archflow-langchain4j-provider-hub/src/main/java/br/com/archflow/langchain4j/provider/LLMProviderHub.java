package br.com.archflow.langchain4j.provider;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.azure.AzureOpenAiChatModel;
import dev.langchain4j.model.azure.AzureOpenAiStreamingChatModel;
import dev.langchain4j.model.bedrock.BedrockChatModel;
import dev.langchain4j.model.bedrock.BedrockStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

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

    /**
     * Per-thread temporary config overrides used by {@link #withProvider}. Keeping the
     * override thread-local means a temporary provider swap never mutates the shared
     * {@link #configs} map, so concurrent callers of {@link #getModel(String)} are not
     * affected by another thread's override.
     */
    private final ThreadLocal<Map<String, LLMProviderConfig>> threadLocalOverrides = new ThreadLocal<>();

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
     *
     * <p>If the calling thread is inside {@link #withProvider}, the temporary
     * override for this config ID is returned instead of the shared config.
     */
    public Optional<LLMProviderConfig> getConfig(String configId) {
        LLMProviderConfig override = overrideFor(configId);
        if (override != null) {
            return Optional.of(override);
        }
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
        LLMProviderConfig override = overrideFor(configId);
        if (override != null) {
            // Ephemeral model for a thread-local override: never touches the shared cache.
            return createModel(override);
        }

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
    public StreamingChatModel getStreamingModel(String configId, boolean useCache) {
        LLMProviderConfig config = getConfig(configId)
                .orElseThrow(() -> new IllegalArgumentException("Config not registered: " + configId));

        if (!config.getProvider().supportsStreaming()) {
            throw new IllegalArgumentException(
                    "Provider " + config.getProvider().getDisplayName() + " does not support streaming");
        }

        if (overrideFor(configId) != null) {
            // Ephemeral model for a thread-local override: never touches the shared cache.
            return createStreamingModel(config);
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
    ChatModel createModel(LLMProviderConfig config) {
        return switch (config.getProvider()) {
            case OPENAI -> createOpenAiModel(config);
            case ANTHROPIC -> createAnthropicModel(config);
            case AZURE_OPENAI -> createAzureOpenAiModel(config);
            case GEMINI -> createGeminiModel(config);
            case OLLAMA -> createOllamaModel(config);
            case BEDROCK -> createBedrockModel(config);
            case HUGGINGFACE -> createHuggingFaceModel(config);
            case WATSONX -> createWatsonxModel(config);
            case VERTEX_AI -> createVertexAiModel(config);
            // OpenAI-compatible providers: same OpenAI client, provider-specific
            // compatibility endpoint (see LLMProvider baseUrl for each).
            case DEEPSEEK, TONGYI, MISTRAL, COHERE, QIANFAN, HUNYUAN, OPENROUTER ->
                    createOpenAiCompatibleModel(config);
        };
    }

    /**
     * Creates a new StreamingChatModel from the given configuration.
     *
     * <p>Every provider whose {@link LLMProvider#supportsStreaming()} returns
     * {@code true} MUST be handled here; providers without a streaming
     * implementation must declare {@code supportsStreaming = false}.
     */
    StreamingChatModel createStreamingModel(LLMProviderConfig config) {
        return switch (config.getProvider()) {
            case OPENAI -> openAiStreaming(config, config.getBaseUrl());
            case ANTHROPIC -> anthropicStreaming(config);
            case AZURE_OPENAI -> createAzureOpenAiStreamingModel(config);
            case GEMINI -> createGeminiStreamingModel(config);
            case OLLAMA -> createOllamaStreamingModel(config);
            case BEDROCK -> createBedrockStreamingModel(config);
            case WATSONX -> createWatsonxStreamingModel(config);
            case VERTEX_AI -> createVertexAiStreamingModel(config);
            // OpenAI-compatible providers stream through the OpenAI streaming client
            // using the SAME default baseUrl as the blocking path.
            case DEEPSEEK, TONGYI, MISTRAL, COHERE, QIANFAN, HUNYUAN, OPENROUTER ->
                    openAiStreaming(config, effectiveBaseUrl(config));
            case HUGGINGFACE -> throw new UnsupportedOperationException(
                    "Streaming is not implemented for provider Hugging Face "
                            + "(langchain4j-hugging-face provides no streaming chat model)");
        };
    }

    /**
     * Resolves the base URL used for a provider: the config override if present,
     * otherwise the provider's default endpoint. Both the blocking and streaming
     * paths of OpenAI-compatible providers use this same resolution.
     */
    static String effectiveBaseUrl(LLMProviderConfig config) {
        return config.getBaseUrl() != null ? config.getBaseUrl() : config.getProvider().getBaseUrl();
    }

    /**
     * Runs a model factory, translating a missing-class error (dependency excluded
     * at runtime) into an exception that names the class that was actually missing
     * and preserves the original cause.
     */
    private static <T> T requireDependency(String providerName, String artifact, Supplier<T> factory) {
        try {
            return factory.get();
        } catch (NoClassDefFoundError e) {
            throw new IllegalStateException(
                    providerName + " provider requires the '" + artifact + "' dependency on the classpath"
                            + " (missing class: " + e.getMessage() + ")", e);
        }
    }

    private StreamingChatModel openAiStreaming(LLMProviderConfig config, String baseUrl) {
        var builder = OpenAiStreamingChatModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getModelId())
                .temperature(config.getTemperature())
                .timeout(config.getTimeout());
        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
        }
        if (config.getTopP() != null) {
            builder.topP(config.getTopP());
        }
        if (config.getMaxTokens() != null) {
            builder.maxTokens(config.getMaxTokens());
        }
        return builder.build();
    }

    private StreamingChatModel anthropicStreaming(LLMProviderConfig config) {
        var builder = AnthropicStreamingChatModel.builder()
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
        return builder.build();
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

    /**
     * Blocking model for providers exposing an OpenAI-compatible endpoint
     * (DeepSeek, Tongyi/Qwen, Mistral, Cohere compat, Qianfan v2, Hunyuan, OpenRouter).
     */
    private ChatModel createOpenAiCompatibleModel(LLMProviderConfig config) {
        var builder = OpenAiChatModel.builder()
                .baseUrl(effectiveBaseUrl(config))
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

    private StreamingChatModel createAzureOpenAiStreamingModel(LLMProviderConfig config) {
        String deploymentId = config.getExtraParam("azure.deploymentId", String.class,
                config.getModelId());

        var builder = AzureOpenAiStreamingChatModel.builder()
                .endpoint(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .deploymentName(deploymentId)
                .temperature(config.getTemperature())
                .timeout(config.getTimeout());
        if (config.getTopP() != null) {
            builder.topP(config.getTopP());
        }
        if (config.getMaxTokens() != null) {
            builder.maxTokens(config.getMaxTokens());
        }
        return builder.build();
    }

    private ChatModel createGeminiModel(LLMProviderConfig config) {
        return requireDependency("Google Gemini", "dev.langchain4j:langchain4j-google-ai-gemini", () -> {
            var builder = GoogleAiGeminiChatModel.builder()
                    .apiKey(config.getApiKey())
                    .modelName(config.getModelId())
                    .temperature(config.getTemperature())
                    .timeout(config.getTimeout());
            if (config.getBaseUrl() != null) {
                builder.baseUrl(config.getBaseUrl());
            }
            if (config.getTopP() != null) {
                builder.topP(config.getTopP());
            }
            if (config.getMaxTokens() != null) {
                builder.maxOutputTokens(config.getMaxTokens());
            }
            return builder.build();
        });
    }

    private StreamingChatModel createGeminiStreamingModel(LLMProviderConfig config) {
        return requireDependency("Google Gemini", "dev.langchain4j:langchain4j-google-ai-gemini", () -> {
            var builder = GoogleAiGeminiStreamingChatModel.builder()
                    .apiKey(config.getApiKey())
                    .modelName(config.getModelId())
                    .temperature(config.getTemperature())
                    .timeout(config.getTimeout());
            if (config.getBaseUrl() != null) {
                builder.baseUrl(config.getBaseUrl());
            }
            if (config.getTopP() != null) {
                builder.topP(config.getTopP());
            }
            if (config.getMaxTokens() != null) {
                builder.maxOutputTokens(config.getMaxTokens());
            }
            return builder.build();
        });
    }

    private ChatModel createOllamaModel(LLMProviderConfig config) {
        return requireDependency("Ollama", "dev.langchain4j:langchain4j-ollama", () -> {
            var builder = OllamaChatModel.builder()
                    .baseUrl(effectiveBaseUrl(config))
                    .modelName(config.getModelId())
                    .temperature(config.getTemperature())
                    .timeout(config.getTimeout());
            if (config.getTopP() != null) {
                builder.topP(config.getTopP());
            }
            if (config.getMaxTokens() != null) {
                builder.numPredict(config.getMaxTokens());
            }
            return builder.build();
        });
    }

    private StreamingChatModel createOllamaStreamingModel(LLMProviderConfig config) {
        return requireDependency("Ollama", "dev.langchain4j:langchain4j-ollama", () -> {
            var builder = OllamaStreamingChatModel.builder()
                    .baseUrl(effectiveBaseUrl(config))
                    .modelName(config.getModelId())
                    .temperature(config.getTemperature())
                    .timeout(config.getTimeout());
            if (config.getTopP() != null) {
                builder.topP(config.getTopP());
            }
            if (config.getMaxTokens() != null) {
                builder.numPredict(config.getMaxTokens());
            }
            return builder.build();
        });
    }

    private ChatModel createBedrockModel(LLMProviderConfig config) {
        return requireDependency("AWS Bedrock", "dev.langchain4j:langchain4j-bedrock", () -> {
            var builder = BedrockChatModel.builder()
                    .modelId(config.getModelId())
                    .timeout(config.getTimeout())
                    .defaultRequestParameters(bedrockRequestParameters(config));

            Region region = resolveAwsRegion(config);
            if (region != null) {
                builder.region(region);
            }

            StaticCredentialsProvider credentials = awsStaticCredentials(config);
            if (credentials != null) {
                var clientBuilder = BedrockRuntimeClient.builder().credentialsProvider(credentials);
                if (region != null) {
                    clientBuilder.region(region);
                }
                builder.client(clientBuilder.build());
            }
            return builder.build();
        });
    }

    private StreamingChatModel createBedrockStreamingModel(LLMProviderConfig config) {
        return requireDependency("AWS Bedrock", "dev.langchain4j:langchain4j-bedrock", () -> {
            var builder = BedrockStreamingChatModel.builder()
                    .modelId(config.getModelId())
                    .timeout(config.getTimeout())
                    .defaultRequestParameters(bedrockRequestParameters(config));

            Region region = resolveAwsRegion(config);
            if (region != null) {
                builder.region(region);
            }

            StaticCredentialsProvider credentials = awsStaticCredentials(config);
            if (credentials != null) {
                var clientBuilder = BedrockRuntimeAsyncClient.builder().credentialsProvider(credentials);
                if (region != null) {
                    clientBuilder.region(region);
                }
                builder.client(clientBuilder.build());
            }
            return builder.build();
        });
    }

    private ChatRequestParameters bedrockRequestParameters(LLMProviderConfig config) {
        var params = ChatRequestParameters.builder()
                .temperature(config.getTemperature());
        if (config.getTopP() != null) {
            params.topP(config.getTopP());
        }
        if (config.getMaxTokens() != null) {
            params.maxOutputTokens(config.getMaxTokens());
        }
        return params.build();
    }

    /**
     * Resolves the AWS region: explicit config (extraParam {@code aws.region}, set by
     * {@code LLMProviderConfig.Builder.bedrock(...)}) first, then the standard AWS
     * environment variables. Returns {@code null} to fall back to the SDK/langchain4j
     * default (us-east-1).
     */
    private Region resolveAwsRegion(LLMProviderConfig config) {
        String region = config.getExtraParam("aws.region", String.class);
        if (region == null || region.isBlank()) {
            region = System.getenv("AWS_REGION");
        }
        if (region == null || region.isBlank()) {
            region = System.getenv("AWS_DEFAULT_REGION");
        }
        return (region == null || region.isBlank()) ? null : Region.of(region);
    }

    /**
     * Static AWS credentials if both keys were configured via
     * {@code LLMProviderConfig.Builder.bedrock(region, accessKey, secretKey)};
     * otherwise {@code null} to use the AWS default credentials provider chain.
     */
    private StaticCredentialsProvider awsStaticCredentials(LLMProviderConfig config) {
        String accessKey = config.getExtraParam("aws.accessKeyId", String.class);
        String secretKey = config.getExtraParam("aws.secretKeyId", String.class);
        if (accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank()) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        }
        return null;
    }

    private ChatModel createHuggingFaceModel(LLMProviderConfig config) {
        return requireDependency("Hugging Face", "dev.langchain4j:langchain4j-hugging-face", () -> {
            var builder = HuggingFaceChatModel.builder()
                    .accessToken(config.getApiKey())
                    .modelId(config.getModelId())
                    .temperature(config.getTemperature())
                    .timeout(config.getTimeout());
            if (config.getBaseUrl() != null) {
                builder.baseUrl(config.getBaseUrl());
            }
            if (config.getMaxTokens() != null) {
                builder.maxNewTokens(config.getMaxTokens());
            }
            return builder.build();
        });
    }

    private ChatModel createWatsonxModel(LLMProviderConfig config) {
        String projectId = requireWatsonxScope(config);
        String spaceId = config.getExtraParam("watsonx.spaceId", String.class);
        return requireDependency("IBM Watsonx", "dev.langchain4j:langchain4j-watsonx", () -> {
            var builder = WatsonxChatModel.builder()
                    .baseUrl(effectiveBaseUrl(config))
                    .apiKey(config.getApiKey())
                    .modelName(config.getModelId())
                    .temperature(config.getTemperature())
                    .timeout(config.getTimeout());
            if (projectId != null) {
                builder.projectId(projectId);
            }
            if (spaceId != null && !spaceId.isBlank()) {
                builder.spaceId(spaceId);
            }
            if (config.getTopP() != null) {
                builder.topP(config.getTopP());
            }
            if (config.getMaxTokens() != null) {
                builder.maxOutputTokens(config.getMaxTokens());
            }
            return builder.build();
        });
    }

    private StreamingChatModel createWatsonxStreamingModel(LLMProviderConfig config) {
        String projectId = requireWatsonxScope(config);
        String spaceId = config.getExtraParam("watsonx.spaceId", String.class);
        return requireDependency("IBM Watsonx", "dev.langchain4j:langchain4j-watsonx", () -> {
            var builder = WatsonxStreamingChatModel.builder()
                    .baseUrl(effectiveBaseUrl(config))
                    .apiKey(config.getApiKey())
                    .modelName(config.getModelId())
                    .temperature(config.getTemperature())
                    .timeout(config.getTimeout());
            if (projectId != null) {
                builder.projectId(projectId);
            }
            if (spaceId != null && !spaceId.isBlank()) {
                builder.spaceId(spaceId);
            }
            if (config.getTopP() != null) {
                builder.topP(config.getTopP());
            }
            if (config.getMaxTokens() != null) {
                builder.maxOutputTokens(config.getMaxTokens());
            }
            return builder.build();
        });
    }

    /**
     * Validates the Watsonx scope (projectId or spaceId) and returns the projectId
     * (may be {@code null} when only spaceId is configured).
     */
    private String requireWatsonxScope(LLMProviderConfig config) {
        String projectId = config.getExtraParam("watsonx.projectId", String.class);
        String spaceId = config.getExtraParam("watsonx.spaceId", String.class);
        boolean hasProject = projectId != null && !projectId.isBlank();
        boolean hasSpace = spaceId != null && !spaceId.isBlank();
        if (!hasProject && !hasSpace) {
            throw new IllegalArgumentException(
                    "IBM Watsonx requires extraParam 'watsonx.projectId' (or 'watsonx.spaceId'). "
                            + "Use LLMProviderConfig.builder().watsonx(projectId, apiKey, endpoint).");
        }
        return hasProject ? projectId : null;
    }

    private ChatModel createVertexAiModel(LLMProviderConfig config) {
        return requireDependency("Vertex AI", "dev.langchain4j:langchain4j-vertex-ai-gemini", () -> {
            var builder = VertexAiGeminiChatModel.builder()
                    .project(requireVertexParam(config, "vertex.project"))
                    .location(requireVertexParam(config, "vertex.location"))
                    .modelName(config.getModelId());
            if (config.getTemperature() != null) {
                builder.temperature(config.getTemperature().floatValue());
            }
            if (config.getTopP() != null) {
                builder.topP(config.getTopP().floatValue());
            }
            if (config.getMaxTokens() != null) {
                builder.maxOutputTokens(config.getMaxTokens());
            }
            return builder.build();
        });
    }

    private StreamingChatModel createVertexAiStreamingModel(LLMProviderConfig config) {
        return requireDependency("Vertex AI", "dev.langchain4j:langchain4j-vertex-ai-gemini", () -> {
            var builder = VertexAiGeminiStreamingChatModel.builder()
                    .project(requireVertexParam(config, "vertex.project"))
                    .location(requireVertexParam(config, "vertex.location"))
                    .modelName(config.getModelId());
            if (config.getTemperature() != null) {
                builder.temperature(config.getTemperature().floatValue());
            }
            if (config.getTopP() != null) {
                builder.topP(config.getTopP().floatValue());
            }
            if (config.getMaxTokens() != null) {
                builder.maxOutputTokens(config.getMaxTokens());
            }
            return builder.build();
        });
    }

    private String requireVertexParam(LLMProviderConfig config, String key) {
        String value = config.getExtraParam(key, String.class);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Vertex AI requires extraParam '" + key + "'. "
                            + "Use LLMProviderConfig.builder().vertexAi(project, location, apiKey); "
                            + "authentication uses Google Application Default Credentials.");
        }
        return value;
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
     * <p>The override is <b>thread-local</b>: only the calling thread sees the
     * temporary configuration, so the shared config registry is never mutated and
     * concurrent threads using the same config ID are unaffected. Models built for
     * the override are ephemeral and never stored in the shared model cache.
     *
     * @param configId The base configuration ID
     * @param tempConfig Temporary configuration to use
     * @param operation The operation to execute
     * @param <T> The result type
     * @return The result of the operation
     */
    public <T> T withProvider(String configId, LLMProviderConfig tempConfig, Supplier<T> operation) {
        tempConfig.validate();

        Map<String, LLMProviderConfig> overrides = threadLocalOverrides.get();
        if (overrides == null) {
            overrides = new HashMap<>();
            threadLocalOverrides.set(overrides);
        }
        LLMProviderConfig previous = overrides.put(configId, tempConfig);
        try {
            return operation.get();
        } finally {
            if (previous != null) {
                overrides.put(configId, previous);
            } else {
                overrides.remove(configId);
            }
            if (overrides.isEmpty()) {
                threadLocalOverrides.remove();
            }
        }
    }

    private LLMProviderConfig overrideFor(String configId) {
        Map<String, LLMProviderConfig> overrides = threadLocalOverrides.get();
        return overrides != null ? overrides.get(configId) : null;
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
