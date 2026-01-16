package br.com.archflow.langchain4j.anthropic;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.model.engine.ExecutionContext;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.memory.ChatMemory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Adapter para chat em streaming com Anthropic Claude usando LangChain4j 1.10.0.
 *
 * <p>Este adapter permite receber respostas do modelo Claude em streaming, onde os chunks
 * da resposta são entregues conforme são gerados.
 *
 * <p>Exemplo de configuração:
 * <pre>{@code
 * Map<String, Object> config = Map.of(
 *     "api.key", "sua-chave-api-anthropic",
 *     "model.name", "claude-3-5-sonnet-20241022",
 *     "temperature", 0.7,
 *     "maxTokens", 4096
 * );
 * }</pre>
 *
 * <p>Operações suportadas:
 * <ul>
 *   <li>{@code generateStream} - Gera resposta em streaming a partir de uma entrada</li>
 *   <li>{@code chatStream} - Chat em streaming com memória de conversa</li>
 * </ul>
 */
public class AnthropicStreamingChatAdapter implements LangChainAdapter {
    private StreamingChatModel model;
    private Map<String, Object> config;

    @Override
    public void validate(Map<String, Object> properties) {
        if (properties == null) {
            throw new IllegalArgumentException("Properties cannot be null");
        }

        String apiKey = (String) properties.get("api.key");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Anthropic API key is required");
        }

        String modelName = (String) properties.getOrDefault("model.name", "claude-3-5-sonnet-20241022");
        if (modelName == null || modelName.trim().isEmpty()) {
            throw new IllegalArgumentException("Model name cannot be empty");
        }

        Object temperature = properties.get("temperature");
        if (temperature != null) {
            if (!(temperature instanceof Number)) {
                throw new IllegalArgumentException("Temperature must be a number");
            }
            double temp = ((Number) temperature).doubleValue();
            if (temp < 0.0 || temp > 1.0) {
                throw new IllegalArgumentException("Temperature must be between 0.0 and 1.0 for Claude");
            }
        }

        Object maxTokens = properties.get("maxTokens");
        if (maxTokens != null) {
            if (!(maxTokens instanceof Number) || ((Number) maxTokens).intValue() <= 0) {
                throw new IllegalArgumentException("MaxTokens must be a positive number");
            }
        }
    }

    @Override
    public void configure(Map<String, Object> properties) {
        validate(properties);
        this.config = properties;

        String apiKey = (String) properties.get("api.key");
        String modelName = (String) properties.getOrDefault("model.name", "claude-3-5-sonnet-20241022");
        Double temperature = ((Number) properties.getOrDefault("temperature", 0.7)).doubleValue();
        Integer maxTokens = properties.get("maxTokens") != null
                ? ((Number) properties.get("maxTokens")).intValue()
                : 4096;

        // LangChain4j 1.10.0: Builder pattern para streaming Anthropic
        this.model = AnthropicStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build();
    }

    @Override
    public synchronized Object execute(String operation, Object input, ExecutionContext context) throws Exception {
        if (model == null) {
            throw new IllegalStateException("Adapter not configured. Call configure() first.");
        }

        if ("generateStream".equals(operation)) {
            if (!(input instanceof String)) {
                throw new IllegalArgumentException("Input must be a string for 'generateStream' operation");
            }
            return generateStream((String) input);
        }

        if ("chatStream".equals(operation)) {
            if (!(input instanceof String)) {
                throw new IllegalArgumentException("Chat input must be a string");
            }

            ChatMemory memory = context.getChatMemory();
            if (memory == null) {
                throw new IllegalStateException("Chat memory not available in context");
            }

            return chatStream((String) input, memory);
        }

        throw new IllegalArgumentException("Unsupported operation: " + operation);
    }

    /**
     * Gera resposta em streaming a partir de uma entrada.
     *
     * @param input Texto de entrada
     * @return CompletableFuture que completa com a resposta completa
     */
    private CompletableFuture<String> generateStream(String input) {
        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        // LangChain4j 1.10.0: StreamingChatModel.chat(String, StreamingChatResponseHandler)
        model.chat(input, new StreamingChatResponseHandler() {
            private final StringBuilder fullResponse = new StringBuilder();

            @Override
            public void onPartialResponse(String partialResponse) {
                // Chunk recebido - pode ser processado aqui se necessário
                fullResponse.append(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                // Resposta completa recebida
                resultFuture.complete(completeResponse.aiMessage().text());
            }

            @Override
            public void onError(Throwable error) {
                resultFuture.completeExceptionally(error);
            }
        });

        return resultFuture;
    }

    /**
     * Chat em streaming com memória de conversa.
     *
     * @param input Mensagem do usuário
     * @param memory Memória de chat
     * @return CompletableFuture que completa com a resposta completa
     */
    private CompletableFuture<String> chatStream(String input, ChatMemory memory) {
        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        UserMessage userMessage = UserMessage.from(input);
        memory.add(userMessage);

        // LangChain4j 1.10.0: StreamingChatModel.chat(List<ChatMessage>, StreamingChatResponseHandler)
        model.chat(memory.messages(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                // Chunk recebido
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                AiMessage aiMessage = completeResponse.aiMessage();
                memory.add(aiMessage);
                resultFuture.complete(aiMessage.text());
            }

            @Override
            public void onError(Throwable error) {
                resultFuture.completeExceptionally(error);
            }
        });

        return resultFuture;
    }

    @Override
    public void shutdown() {
        this.model = null;
        this.config = null;
    }
}
