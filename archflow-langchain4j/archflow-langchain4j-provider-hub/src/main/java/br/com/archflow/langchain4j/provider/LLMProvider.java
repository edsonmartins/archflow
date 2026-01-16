package br.com.archflow.langchain4j.provider;

import java.util.*;

/**
 * Enumeration of supported LLM providers in the Multi-LLM Provider Hub.
 *
 * <p>This enum provides a unified interface for 15+ LLM providers,
 * enabling runtime switching and consistent configuration across providers.
 *
 * <p>Supported providers include:
 * <ul>
 *   <li>OpenAI (GPT-4, GPT-4o, o1, o3-mini)</li>
 *   <li>Anthropic (Claude 3.5/3.7 Sonnet)</li>
 *   <li>Azure OpenAI</li>
 *   <li>Google Gemini</li>
 *   <li>AWS Bedrock</li>
 *   <li>Hugging Face</li>
 *   <li>Ollama</li>
 *   <li>Mistral AI</li>
 *   <li>Cohere</li>
 *   <li>DeepSeek</li>
 *   <li>Alibaba Tongyi (Qwen)</li>
 *   <li>Baidu Qianfan (Ernie)</li>
 *   <li>Tencent Hunyuan</li>
 *   <li>IBM Watsonx</li>
 *   <li>Vertex AI</li>
 * </ul>
 */
public enum LLMProvider {

    /**
     * OpenAI - GPT-4, GPT-4o, GPT-4o-mini, o1, o3-mini
     */
    OPENAI(
        "openai",
        "OpenAI",
        "https://api.openai.com/v1",
        true,
        true,
        List.of(
            ModelInfo.of("gpt-4o", "GPT-4o", 128000, 2.0),
            ModelInfo.of("gpt-4o-mini", "GPT-4o Mini", 128000, 2.0),
            ModelInfo.of("gpt-4-turbo", "GPT-4 Turbo", 128000, 2.0),
            ModelInfo.of("gpt-4", "GPT-4", 8192, 2.0),
            ModelInfo.of("o1", "o1", 200000, 1.0),
            ModelInfo.of("o3-mini", "o3-mini", 200000, 1.0)
        )
    ),

    /**
     * Anthropic - Claude 3.5 Sonnet, Claude 3.7 Sonnet
     */
    ANTHROPIC(
        "anthropic",
        "Anthropic",
        "https://api.anthropic.com/v1",
        true,
        true,
        List.of(
            ModelInfo.of("claude-sonnet-4-20250514", "Claude 3.7 Sonnet", 200000, 1.0),
            ModelInfo.of("claude-3-5-sonnet-20241022", "Claude 3.5 Sonnet", 200000, 1.0),
            ModelInfo.of("claude-3-5-sonnet-20240620", "Claude 3.5 Sonnet (June)", 200000, 1.0),
            ModelInfo.of("claude-3-opus-20240229", "Claude 3 Opus", 200000, 1.0),
            ModelInfo.of("claude-3-haiku-20240307", "Claude 3 Haiku", 200000, 1.0)
        )
    ),

    /**
     * Azure OpenAI - GPT models hosted on Azure
     */
    AZURE_OPENAI(
        "azure-openai",
        "Azure OpenAI",
        "https://{resource}.openai.azure.com",
        true,
        true,
        List.of(
            ModelInfo.of("gpt-4o", "GPT-4o (Azure)", 128000, 2.0),
            ModelInfo.of("gpt-4o-mini", "GPT-4o Mini (Azure)", 128000, 2.0),
            ModelInfo.of("gpt-4-turbo", "GPT-4 Turbo (Azure)", 128000, 2.0)
        )
    ),

    /**
     * Google Gemini - Gemini Pro, Gemini Flash
     */
    GEMINI(
        "gemini",
        "Google Gemini",
        "https://generativelanguage.googleapis.com/v1",
        true,
        true,
        List.of(
            ModelInfo.of("gemini-2.0-flash-exp", "Gemini 2.0 Flash Experimental", 1000000, 2.0),
            ModelInfo.of("gemini-1.5-pro", "Gemini 1.5 Pro", 2000000, 2.0),
            ModelInfo.of("gemini-1.5-flash", "Gemini 1.5 Flash", 1000000, 2.0),
            ModelInfo.of("gemini-1.0-pro", "Gemini 1.0 Pro", 91728, 2.0)
        )
    ),

    /**
     * AWS Bedrock - Multiple models via AWS
     */
    BEDROCK(
        "bedrock",
        "AWS Bedrock",
        "https://bedrock-runtime.{region}.amazonaws.com",
        true,
        true,
        List.of(
            ModelInfo.of("anthropic.claude-3-5-sonnet-20241022-v2:0", "Claude 3.5 Sonnet (Bedrock)", 200000, 1.0),
            ModelInfo.of("anthropic.claude-3-opus-20240229-v1:0", "Claude 3 Opus (Bedrock)", 200000, 1.0),
            ModelInfo.of("us.meta.llama3-3-70b-instruct-v1:0", "Llama 3.3 70B (Bedrock)", 128000, 1.0),
            ModelInfo.of("amazon.titan-text-premier-v1:0", "Titan Text Premier (Bedrock)", 32000, 1.0)
        )
    ),

    /**
     * Hugging Face - Open models via Hugging Face Inference API
     */
    HUGGINGFACE(
        "huggingface",
        "Hugging Face",
        "https://api-inference.huggingface.co",
        false,
        true,
        List.of(
            ModelInfo.of("meta-llama/Llama-3.3-70B-Instruct", "Llama 3.3 70B", 128000, 1.0),
            ModelInfo.of("mistralai/Mixtral-8x7B-Instruct-v0.1", "Mixtral 8x7B", 32768, 1.0),
            ModelInfo.of("google/gemma-7b", "Gemma 7B", 8192, 1.0)
        )
    ),

    /**
     * Ollama - Local open-source models
     */
    OLLAMA(
        "ollama",
        "Ollama",
        "http://localhost:11434",
        false,
        false,
        List.of(
            ModelInfo.of("llama3.3", "Llama 3.3", 128000, 1.0),
            ModelInfo.of("llama3.2", "Llama 3.2", 128000, 1.0),
            ModelInfo.of("mistral", "Mistral 7B", 32768, 1.0),
            ModelInfo.of("qwen2.5", "Qwen 2.5", 32768, 1.0),
            ModelInfo.of("deepseek-coder", "DeepSeek Coder", 16384, 1.0),
            ModelInfo.of("phi3", "Phi-3", 128000, 1.0)
        )
    ),

    /**
     * Mistral AI - Mistral models
     */
    MISTRAL(
        "mistral",
        "Mistral AI",
        "https://api.mistral.ai/v1",
        true,
        true,
        List.of(
            ModelInfo.of("mistral-large-2411", "Mistral Large", 128000, 1.0),
            ModelInfo.of("mistral-medium", "Mistral Medium", 32000, 1.0),
            ModelInfo.of("mistral-small", "Mistral Small", 32000, 1.0),
            ModelInfo.of("codestral", "Codestral", 32000, 1.0),
            ModelInfo.of("pixtral-12b", "Pixtral 12B", 128000, 1.0)
        )
    ),

    /**
     * Cohere - Command models
     */
    COHERE(
        "cohere",
        "Cohere",
        "https://api.cohere.ai/v1",
        true,
        true,
        List.of(
            ModelInfo.of("command-r-plus-08-2024", "Command R+", 128000, 1.0),
            ModelInfo.of("command-r-08-2024", "Command R", 128000, 1.0),
            ModelInfo.of("command", "Command", 4096, 1.0)
        )
    ),

    /**
     * DeepSeek - Chinese AI lab
     */
    DEEPSEEK(
        "deepseek",
        "DeepSeek",
        "https://api.deepseek.com/v1",
        true,
        true,
        List.of(
            ModelInfo.of("deepseek-chat", "DeepSeek Chat", 128000, 1.0),
            ModelInfo.of("deepseek-coder", "DeepSeek Coder", 128000, 1.0),
            ModelInfo.of("deepseek-reasoner", "DeepSeek Reasoner", 64000, 1.0)
        )
    ),

    /**
     * Alibaba Tongyi (Qwen) - Chinese cloud provider
     */
    TONGYI(
        "tongyi",
        "Alibaba Tongyi (Qwen)",
        "https://dashscope.aliyuncs.com/compatible-mode/v1",
        true,
        true,
        List.of(
            ModelInfo.of("qwen-max", "Qwen Max", 32000, 1.0),
            ModelInfo.of("qwen-plus", "Qwen Plus", 128000, 1.0),
            ModelInfo.of("qwen-turbo", "Qwen Turbo", 8000, 1.0),
            ModelInfo.of("qwen-long", "Qwen Long", 1000000, 1.0)
        )
    ),

    /**
     * Baidu Qianfan (Ernie) - Chinese cloud provider
     */
    QIANFAN(
        "qianfan",
        "Baidu Qianfan (Ernie)",
        "https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop",
        true,
        true,
        List.of(
            ModelInfo.of("ernie-4.0-turbo-8k", "Ernie 4.0 Turbo", 8192, 1.0),
            ModelInfo.of("ernie-4.0-8k", "Ernie 4.0", 8192, 1.0),
            ModelInfo.of("ernie-speed-8k", "Ernie Speed", 8192, 1.0)
        )
    ),

    /**
     * Tencent Hunyuan - Chinese cloud provider
     */
    HUNYUAN(
        "hunyuan",
        "Tencent Hunyuan",
        "https://hunyuan.tencentcloudapi.com",
        true,
        true,
        List.of(
            ModelInfo.of("hunyuan-pro", "Hunyuan Pro", 32000, 1.0),
            ModelInfo.of("hunyuan-standard", "Hunyuan Standard", 32000, 1.0),
            ModelInfo.of("hunyuan-lite", "Hunyuan Lite", 16000, 1.0)
        )
    ),

    /**
     * IBM Watsonx - Enterprise AI platform
     */
    WATSONX(
        "watsonx",
        "IBM Watsonx",
        "https://us-south.ml.cloud.ibm.com",
        true,
        true,
        List.of(
            ModelInfo.of("ibm/granite-3-8b-instruct", "Granite 3 8B", 128000, 1.0),
            ModelInfo.of("ibm/granite-3-2b-instruct", "Granite 3 2B", 32000, 1.0),
            ModelInfo.of("meta-llama/llama-3-1-70b-instruct", "Llama 3.1 70B (Watsonx)", 128000, 1.0)
        )
    ),

    /**
     * Vertex AI - Google Cloud AI platform
     */
    VERTEX_AI(
        "vertex-ai",
        "Google Vertex AI",
        "https://{location}-aiplatform.googleapis.com/v1",
        true,
        true,
        List.of(
            ModelInfo.of("gemini-2.0-flash-exp", "Gemini 2.0 Flash (Vertex)", 1000000, 2.0),
            ModelInfo.of("gemini-1.5-pro", "Gemini 1.5 Pro (Vertex)", 2000000, 2.0),
            ModelInfo.of("claude-3-5-sonnet@20241022", "Claude 3.5 Sonnet (Vertex)", 200000, 1.0)
        )
    );

    private final String id;
    private final String displayName;
    private final String baseUrl;
    private final boolean requiresApiKey;
    private final boolean supportsStreaming;
    private final List<ModelInfo> models;

    LLMProvider(String id, String displayName, String baseUrl,
                boolean requiresApiKey, boolean supportsStreaming,
                List<ModelInfo> models) {
        this.id = id;
        this.displayName = displayName;
        this.baseUrl = baseUrl;
        this.requiresApiKey = requiresApiKey;
        this.supportsStreaming = supportsStreaming;
        this.models = Collections.unmodifiableList(models);
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public boolean requiresApiKey() {
        return requiresApiKey;
    }

    public boolean supportsStreaming() {
        return supportsStreaming;
    }

    public List<ModelInfo> getModels() {
        return models;
    }

    /**
     * Gets model info by model ID.
     */
    public Optional<ModelInfo> getModel(String modelId) {
        return models.stream()
                .filter(m -> m.id().equals(modelId))
                .findFirst();
    }

    /**
     * Finds provider by ID.
     */
    public static Optional<LLMProvider> fromId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        for (LLMProvider provider : values()) {
            if (provider.id.equalsIgnoreCase(id)) {
                return Optional.of(provider);
            }
        }
        return Optional.empty();
    }

    /**
     * Gets all supported provider IDs.
     */
    public static Set<String> allProviderIds() {
        Set<String> ids = new HashSet<>();
        for (LLMProvider provider : values()) {
            ids.add(provider.id);
        }
        return Collections.unmodifiableSet(ids);
    }

    /**
     * Gets providers that support streaming.
     */
    public static List<LLMProvider> streamingProviders() {
        return Arrays.stream(values())
                .filter(LLMProvider::supportsStreaming)
                .toList();
    }

    /**
     * Gets cloud providers (excluding local like Ollama).
     */
    public static List<LLMProvider> cloudProviders() {
        return Arrays.stream(values())
                .filter(p -> p != OLLAMA)
                .toList();
    }

    /**
     * Gets local providers.
     */
    public static List<LLMProvider> localProviders() {
        return Arrays.stream(values())
                .filter(p -> p == OLLAMA)
                .toList();
    }

    /**
     * Information about a specific model.
     */
    public record ModelInfo(
            String id,
            String name,
            int contextWindow,
            double maxTemperature
    ) {
        public static ModelInfo of(String id, String name, int contextWindow, double maxTemperature) {
            return new ModelInfo(id, name, contextWindow, maxTemperature);
        }
    }
}
