package br.com.archflow.langchain4j.vectorstore.redis;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.langchain4j.core.spi.LangChainAdapterFactory;
import br.com.archflow.model.engine.ExecutionContext;
import dev.langchain4j.community.store.embedding.redis.RedisEmbeddingStore;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;


import java.util.List;
import java.util.Map;

/**
 * Adapter para armazenamento e busca de embeddings usando Redis via LangChain4j.
 * Utiliza o pacote langchain4j-community-redis para integração nativa.
 *
 * <p>Exemplo de configuração:
 * <pre>{@code
 * Map<String, Object> config = Map.of(
 *     "redis.host", "localhost",         // Host do Redis
 *     "redis.port", 6379,               // Porta do Redis
 *     "redis.index", "embeddings",      // Nome do índice (opcional)
 *     "redis.dimension", 1536           // Dimensão dos vetores
 * );
 * }</pre>
 */
public class RedisVectorStoreAdapter implements LangChainAdapter, dev.langchain4j.store.embedding.EmbeddingStore<TextSegment> {
    private volatile RedisEmbeddingStore embeddingStore;
    private Map<String, Object> config;

    @Override
    public void validate(Map<String, Object> properties) {
        if (properties == null) {
            throw new IllegalArgumentException("Properties cannot be null");
        }

        String host = (String) properties.get("redis.host");
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("Redis host is required");
        }

        Object port = properties.get("redis.port");
        if (port != null && !(port instanceof Number)) {
            throw new IllegalArgumentException("Redis port must be a number");
        }

        Object dimension = properties.get("redis.dimension");
        if (dimension == null || !(dimension instanceof Number)) {
            throw new IllegalArgumentException("Vector dimension is required and must be a number");
        }
    }

    @Override
    public void configure(Map<String, Object> properties) {
        validate(properties);
        this.config = properties;

        String host = (String) properties.get("redis.host");
        int port = (Integer) properties.getOrDefault("redis.port", 6379);
        String indexName = (String) properties.getOrDefault("redis.index", "embeddings");
        int dimension = (Integer) properties.get("redis.dimension");

        this.embeddingStore = RedisEmbeddingStore.builder()
                .host(host)
                .port(port)
                .indexName(indexName)
                .dimension(dimension)
                .build();
    }

    @Override
    public void add(String id, Embedding embedding) {
        embeddingStore.add(id, embedding);
    }

    @Override
    public String add(Embedding embedding) {
        return embeddingStore.add(embedding);
    }

    @Override
    public String add(Embedding embedding, TextSegment embedded) {
        return embeddingStore.add(embedding, embedded);
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        return embeddingStore.addAll(embeddings);
    }

    @Override
    public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> embedded) {
        embeddingStore.addAll(ids, embeddings, embedded);
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        return embeddingStore.search(request);
    }

    @Override
    public Object execute(String operation, Object input, ExecutionContext context) throws Exception {
        if (embeddingStore == null) {
            throw new IllegalStateException("Vector store not configured. Call configure() first.");
        }

        if ("search".equals(operation)) {
            if (!(input instanceof EmbeddingSearchRequest)) {
                throw new IllegalArgumentException("Input must be an EmbeddingSearchRequest for search operation");
            }
            return search((EmbeddingSearchRequest) input);
        }

        throw new IllegalArgumentException("Unsupported operation: " + operation);
    }

    @Override
    public void shutdown() {
        this.embeddingStore = null;
        this.config = null;
    }

}