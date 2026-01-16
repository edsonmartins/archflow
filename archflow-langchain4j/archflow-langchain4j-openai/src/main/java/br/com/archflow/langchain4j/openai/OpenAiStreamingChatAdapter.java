package br.com.archflow.langchain4j.openai;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.model.engine.ExecutionContext;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.memory.ChatMemory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Adapter para chat em streaming com OpenAI usando LangChain4j 1.10.0.
 *
 * <p>Este adapter permite receber respostas do modelo em streaming, onde os chunks
 * da resposta são entregues conforme são gerados, em vez de esperar a resposta completa.
 *
 * <p>Exemplo de configuração:
 * <pre>{@code
 * Map<String, Object> config = Map.of(
 *     "api.key", "sua-chave-api-openai",
 *     "model.name", "gpt-4o",
 *     "temperature", 0.7
 * );
 * }</pre>
 *
 * <p>Operações suportadas:
 * <ul>
 *   <li>{@code generateStream} - Gera resposta em streaming a partir de uma entrada</li>
 *   <li>{@code chatStream} - Chat em streaming com memória de conversa</li>
 * </ul>
 */
public class OpenAiStreamingChatAdapter implements LangChainAdapter {
    private StreamingChatModel model;
    private Map<String, Object> config;

    @Override
    public void validate(Map<String, Object> properties) {
        if (properties == null) {
            throw new IllegalArgumentException("Properties cannot be null");
        }

        String apiKey = (String) properties.get("api.key");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("OpenAI API key is required");
        }

        String modelName = (String) properties.getOrDefault("model.name", "gpt-4o-mini");
        if (modelName == null || modelName.trim().isEmpty()) {
            throw new IllegalArgumentException("Model name cannot be empty");
        }

        Object temperature = properties.get("temperature");
        if (temperature != null) {
            if (!(temperature instanceof Number)) {
                throw new IllegalArgumentException("Temperature must be a number");
            }
            double temp = ((Number) temperature).doubleValue();
            if (temp < 0.0 || temp > 2.0) {
                throw new IllegalArgumentException("Temperature must be between 0.0 and 2.0");
            }
        }
    }

    @Override
    public void configure(Map<String, Object> properties) {
        validate(properties);
        this.config = properties;

        String apiKey = (String) properties.get("api.key");
        String modelName = (String) properties.getOrDefault("model.name", "gpt-4o-mini");
        Double temperature = ((Number) properties.getOrDefault("temperature", 0.7)).doubleValue();
        Integer maxTokens = properties.get("maxTokens") != null
                ? ((Number) properties.get("maxTokens")).intValue()
                : null;

        // LangChain4j 1.10.0: Builder pattern para streaming
        var modelBuilder = OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature);

        if (maxTokens != null) {
            modelBuilder.maxTokens(maxTokens);
        }

        this.model = modelBuilder.build();
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
        StringBuilder fullResponse = new StringBuilder();

        // LangChain4j 1.10.0: StreamingChatModel.chat(String, StreamingChatResponseHandler)
        model.chat(input, new StreamingChatResponseHandler() {
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
