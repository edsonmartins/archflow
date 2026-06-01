package br.com.archflow.langchain4j.openrouter;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.model.config.ResolvedLLMConfig;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.engine.ExecutionKeys;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.memory.ChatMemory;

import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Adapter para OpenRouter via API compatível com OpenAI.
 *
 * <p>OpenRouter roteia chamadas para múltiplos providers (OpenAI, Anthropic,
 * Google, Meta, etc.) via uma API unificada compatível com OpenAI.
 *
 * <p>Suporta fallback automático para um provider local (ex: Ollama)
 * quando OpenRouter está indisponível.
 *
 * <p>Configuração:
 * <ul>
 *   <li>{@code api.key} — Chave de API do OpenRouter (obrigatória)</li>
 *   <li>{@code model.name} — Modelo (ex: "anthropic/claude-3.5-sonnet", default: "openai/gpt-4o-mini")</li>
 *   <li>{@code base.url} — URL base (default: "https://openrouter.ai/api/v1")</li>
 *   <li>{@code temperature} — Temperatura (default: 0.7)</li>
 *   <li>{@code fallback.base.url} — URL do provider fallback (ex: "http://localhost:11434/v1")</li>
 *   <li>{@code fallback.model.name} — Modelo fallback (ex: "llama3.1")</li>
 * </ul>
 */
public class OpenRouterChatAdapter implements LangChainAdapter {

    private final ReentrantLock lock = new ReentrantLock();
    private static final Logger logger = Logger.getLogger(OpenRouterChatAdapter.class.getName());
    private static final String DEFAULT_BASE_URL = "https://openrouter.ai/api/v1";
    private static final String DEFAULT_MODEL = "openai/gpt-4o-mini";

    private ChatModel model;
    private ChatModel fallbackModel;
    private Map<String, Object> config;

    @Override
    public void validate(Map<String, Object> properties) {
        if (properties == null) {
            throw new IllegalArgumentException("Properties cannot be null");
        }
        String apiKey = (String) properties.get("api.key");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("OpenRouter API key is required (api.key)");
        }
    }

    @Override
    public void configure(Map<String, Object> properties) {
        validate(properties);
        this.config = properties;

        String apiKey = (String) properties.get("api.key");
        String modelName = (String) properties.getOrDefault("model.name", DEFAULT_MODEL);
        String baseUrl = (String) properties.getOrDefault("base.url", DEFAULT_BASE_URL);
        Double temperature = ((Number) properties.getOrDefault("temperature", 0.7)).doubleValue();
        Integer maxTokens = properties.get("maxTokens") != null
                ? ((Number) properties.get("maxTokens")).intValue() : 2048;

        this.model = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build();

        // Configurar fallback (ex: Ollama local)
        String fallbackBaseUrl = (String) properties.get("fallback.base.url");
        if (fallbackBaseUrl != null && !fallbackBaseUrl.isBlank()) {
            String fallbackModelName = (String) properties.getOrDefault("fallback.model.name", "llama3.1");
            String fallbackApiKey = (String) properties.getOrDefault("fallback.api.key", "ollama");
            this.fallbackModel = OpenAiChatModel.builder()
                    .apiKey(fallbackApiKey)
                    .baseUrl(fallbackBaseUrl)
                    .modelName(fallbackModelName)
                    .temperature(temperature)
                    .maxTokens(maxTokens)
                    .build();
            logger.info("OpenRouter fallback configured: " + fallbackBaseUrl + " / " + fallbackModelName);
        }

        logger.info("OpenRouter adapter configured: " + baseUrl + " / " + modelName);
    }

    @Override
    public Object execute(String operation, Object input, ExecutionContext context) throws Exception {
        lock.lock();
        try {
            return doExecute(operation, input, context);
        } finally {
            lock.unlock();
        }
    }

    private Object doExecute(String operation, Object input, ExecutionContext context) throws Exception {
            if (model == null) {
                throw new IllegalStateException("Adapter not configured. Call configure() first.");
            }

            // Resolve modelo por tenant/agente via ExecutionContext.variables
            ChatModel resolved = resolveModel(context);

            try {
                return executeWithModel(resolved, operation, input, context);
            } catch (Exception e) {
                if (fallbackModel != null) {
                    logger.log(Level.WARNING,
                            "OpenRouter call failed, falling back to local provider: " + e.getMessage());
                    return executeWithModel(fallbackModel, operation, input, context);
                }
                throw e;
            }
    }

    /**
     * Resolve o modelo a usar com base no contexto. Prioridade:
     * <ol>
     *   <li>{@link ExecutionKeys#LLM_RESOLVED_CONFIG} — config resolvida pela
     *       cadeia de herança (model/temperature/maxTokens/apiKey/baseUrl);</li>
     *   <li>{@link ExecutionKeys#LLM_MODEL} — override legado só do nome;</li>
     *   <li>modelo default configurado.</li>
     * </ol>
     */
    private ChatModel resolveModel(ExecutionContext context) {
        EffectiveModel eff = effectiveModel(context);
        if (eff == null) {
            return model;
        }
        OpenAiChatModel.OpenAiChatModelBuilder b = OpenAiChatModel.builder()
                .apiKey(eff.apiKey())
                .baseUrl(eff.baseUrl())
                .modelName(eff.modelName())
                .temperature(eff.temperature());
        if (eff.maxTokens() != null) {
            b.maxTokens(eff.maxTokens());
        }
        return b.build();
    }

    /**
     * Decisão pura (sem construir modelo) de qual config efetiva usar.
     * {@code null} ⇒ usar o modelo default. Visível ao pacote para testes.
     */
    EffectiveModel effectiveModel(ExecutionContext context) {
        if (context == null) {
            return null;
        }
        Object resolved = context.get(ExecutionKeys.LLM_RESOLVED_CONFIG).orElse(null);
        if (resolved instanceof ResolvedLLMConfig r && r.model() != null && !r.model().isBlank()) {
            String apiKey = strOr(r.additionalConfig().get("apiKey"), (String) config.get("api.key"));
            String baseUrl = strOr(r.additionalConfig().get("baseUrl"),
                    (String) config.getOrDefault("base.url", DEFAULT_BASE_URL));
            Integer maxTokens = r.maxTokens() > 0 ? r.maxTokens() : null;
            return new EffectiveModel(r.model(), r.temperature(), maxTokens, apiKey, baseUrl);
        }
        Object override = context.get(ExecutionKeys.LLM_MODEL).orElse(null);
        if (override instanceof String modelName && !modelName.isBlank()) {
            String apiKey = (String) config.get("api.key");
            String baseUrl = (String) config.getOrDefault("base.url", DEFAULT_BASE_URL);
            double temperature = ((Number) config.getOrDefault("temperature", 0.7)).doubleValue();
            return new EffectiveModel(modelName, temperature, null, apiKey, baseUrl);
        }
        return null;
    }

    private static String strOr(Object value, String fallback) {
        return (value instanceof String s && !s.isBlank()) ? s : fallback;
    }

    /** Parâmetros efetivos do modelo (resultado da resolução de contexto). */
    record EffectiveModel(String modelName, double temperature, Integer maxTokens,
                          String apiKey, String baseUrl) {}

    private Object executeWithModel(ChatModel chatModel, String operation, Object input,
                                     ExecutionContext context) throws Exception {
        if ("generate".equals(operation)) {
            if (!(input instanceof String)) {
                throw new IllegalArgumentException("Input must be a string for 'generate'");
            }
            return chatModel.chat((String) input);
        }

        if ("chat".equals(operation)) {
            if (!(input instanceof String)) {
                throw new IllegalArgumentException("Chat input must be a string");
            }
            ChatMemory memory = context.getChatMemory();
            if (memory == null) {
                throw new IllegalStateException("Chat memory not available in context");
            }
            UserMessage userMessage = UserMessage.from((String) input);
            memory.add(userMessage);
            ChatResponse response = chatModel.chat(memory.messages());
            memory.add(response.aiMessage());
            return response.aiMessage().text();
        }

        throw new IllegalArgumentException("Unsupported operation: " + operation);
    }

    @Override
    public void shutdown() {
        this.model = null;
        this.fallbackModel = null;
        this.config = null;
    }

    /**
     * Retorna true se um fallback está configurado.
     */
    public boolean hasFallback() {
        return fallbackModel != null;
    }
}
