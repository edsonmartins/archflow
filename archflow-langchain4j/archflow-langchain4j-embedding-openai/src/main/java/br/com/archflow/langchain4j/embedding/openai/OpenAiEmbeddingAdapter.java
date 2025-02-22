package br.com.archflow.langchain4j.embedding.openai;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.langchain4j.core.spi.LangChainAdapterFactory;
import br.com.archflow.model.engine.ExecutionContext;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Adapter para o modelo de embeddings da OpenAI.
 * Gera embeddings de texto usando a API da OpenAI (text-embedding-ada-002).
 *
 * <p>Exemplo de configuração:
 * <pre>{@code
 * Map<String, Object> config = Map.of(
 *     "openai.api.key", "sk-...",           // API key da OpenAI
 *     "openai.model", "text-embedding-ada-002", // Modelo (opcional)
 *     "openai.timeout", 30,                 // Timeout em segundos (opcional)
 *     "openai.maxRetries", 3                // Máximo de retentativas (opcional)
 * );
 * }</pre>
 *
 * <p>Operações suportadas:
 * <ul>
 *   <li>{@code embed} - Gera embedding de um texto</li>
 *   <li>{@code embedBatch} - Gera embeddings de múltiplos textos</li>
 * </ul>
 */
public class OpenAiEmbeddingAdapter implements LangChainAdapter, EmbeddingModel {
    private volatile OpenAiEmbeddingModel embeddingModel;
    private Map<String, Object> config;

    /**
     * Valida as configurações fornecidas para o adapter.
     *
     * @param properties Map com as configurações
     * @throws IllegalArgumentException se as configurações forem inválidas
     */
    @Override
    public void validate(Map<String, Object> properties) {
        if (properties == null) {
            throw new IllegalArgumentException("Properties cannot be null");
        }

        String apiKey = (String) properties.get("openai.api.key");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("OpenAI API key is required");
        }

        Object timeout = properties.get("openai.timeout");
        if (timeout != null) {
            if (!(timeout instanceof Number) || ((Number) timeout).intValue() <= 0) {
                throw new IllegalArgumentException("Timeout must be a positive number");
            }
        }

        Object maxRetries = properties.get("openai.maxRetries");
        if (maxRetries != null) {
            if (!(maxRetries instanceof Number) || ((Number) maxRetries).intValue() < 0 || ((Number) maxRetries).intValue() > 10) {
                throw new IllegalArgumentException("Max retries must be a number between 0 and 10");
            }
        }
    }

    /**
     * Configura o adapter com as propriedades especificadas.
     *
     * <p>Requer as seguintes configurações:
     * <ul>
     *   <li>{@code openai.api.key} - Chave de API da OpenAI</li>
     * </ul>
     * <p>Configurações opcionais:
     * <ul>
     *   <li>{@code openai.model} - Nome do modelo (default: "text-embedding-ada-002")</li>
     *   <li>{@code openai.timeout} - Timeout em segundos (default: 30)</li>
     *   <li>{@code openai.maxRetries} - Máximo de retentativas (default: 3)</li>
     * </ul>
     *
     * @param properties Map com as configurações
     * @throws IllegalArgumentException se as configurações forem inválidas
     */
    @Override
    public void configure(Map<String, Object> properties) {
        validate(properties);
        this.config = properties;

        String apiKey = (String) properties.get("openai.api.key");
        String model = (String) properties.getOrDefault("openai.model", "text-embedding-ada-002");
        int timeout = (Integer) properties.getOrDefault("openai.timeout", 30);
        int maxRetries = (Integer) properties.getOrDefault("openai.maxRetries", 3);

        this.embeddingModel = OpenAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName(model)
                .timeout(Duration.ofSeconds(timeout))
                .maxRetries(maxRetries)
                .build();
    }

    /**
     * Executa operações no modelo de embeddings da OpenAI.
     *
     * @param operation Nome da operação ("embed" ou "embedBatch")
     * @param input     Para "embed": String ou {@link TextSegment}<br>Para "embedBatch": List de String ou {@link TextSegment}
     * @param context   Contexto de execução (não utilizado atualmente)
     * @return Para "embed": {@link Response}&lt;{@link Embedding}&gt;<br>Para "embedBatch": {@link Response}&lt;List&lt;{@link Embedding}&gt;&gt;
     * @throws IllegalArgumentException se a operação ou o input for inválido
     * @throws IllegalStateException    se o adapter não estiver configurado
     * @throws RuntimeException         se ocorrer um erro durante a execução (ex.: falha de rede)
     */
    @Override
    public synchronized Object execute(String operation, Object input, ExecutionContext context) throws Exception {
        if (embeddingModel == null) {
            throw new IllegalStateException("Embedding model not configured. Call configure() first.");
        }

        try {
            if ("embed".equals(operation)) {
                if (input instanceof String) {
                    return embed(TextSegment.from((String) input));
                }
                if (input instanceof TextSegment) {
                    return embed((TextSegment) input);
                }
                throw new IllegalArgumentException("Input must be a String or TextSegment for embed operation");
            }

            if ("embedBatch".equals(operation)) {
                if (!(input instanceof List)) {
                    throw new IllegalArgumentException("Input must be a List for embedBatch operation");
                }
                List<?> inputs = (List<?>) input;
                if (inputs.isEmpty()) {
                    throw new IllegalArgumentException("Input list cannot be empty");
                }

                if (inputs.get(0) instanceof String) {
                    List<TextSegment> segments = ((List<String>) input).stream()
                            .map(TextSegment::from)
                            .toList();
                    return embedAll(segments);
                }
                if (inputs.get(0) instanceof TextSegment) {
                    return embedAll((List<TextSegment>) input);
                }
                throw new IllegalArgumentException("Input must be a List of String or TextSegment for embedBatch operation");
            }

            throw new IllegalArgumentException("Unsupported operation: " + operation);
        } catch (Exception e) {
            throw new RuntimeException("Error executing operation: " + operation, e);
        }
    }

    /**
     * Gera um embedding para um único segmento de texto.
     *
     * @param text O segmento de texto a ser convertido em embedding
     * @return Resposta contendo o embedding gerado
     */
    @Override
    public Response<Embedding> embed(TextSegment text) {
        return embeddingModel.embed(text);
    }

    /**
     * Gera embeddings para uma lista de segmentos de texto.
     *
     * @param texts Lista de segmentos de texto a serem convertidos em embeddings
     * @return Resposta contendo a lista de embeddings gerados
     */
    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> texts) {
        return embeddingModel.embedAll(texts);
    }

    /**
     * Libera recursos utilizados pelo adapter.
     *
     * <p>Define o modelo e as configurações como null. Recursos internos do {@link OpenAiEmbeddingModel}
     * (ex.: cliente HTTP) são gerenciados pelo LangChain4j.
     */
    @Override
    public void shutdown() {
        this.embeddingModel = null;
        this.config = null;
    }

    public static class Factory implements LangChainAdapterFactory {
        @Override
        public String getProvider() {
            return "openai";
        }

        @Override
        public LangChainAdapter createAdapter(Map<String, Object> properties) {
            OpenAiEmbeddingAdapter adapter = new OpenAiEmbeddingAdapter();
            adapter.configure(properties);
            return adapter;
        }

        @Override
        public boolean supports(String type) {
            return "embedding".equals(type);
        }
    }
}