package br.com.archflow.langchain4j.vectorstore.redis;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.langchain4j.core.spi.LangChainAdapterFactory;
import br.com.archflow.model.engine.ExecutionContext;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Adapter para armazenamento e busca de embeddings usando Redis.
 *
 * <p><b>NOTA:</b> O módulo langchain4j-community-redis não está disponível na versão 1.10.0.
 * Esta implementação usa Jedis diretamente para armazenamento de vetores no Redis.
 *
 * <p>Exemplo de configuração:
 * <pre>{@code
 * Map<String, Object> config = Map.of(
 *     "redis.host", "localhost",         // Host do Redis
 *     "redis.port", 6379,               // Porta do Redis
 *     "redis.prefix", "embedding:",     // Prefixo para chaves (opcional)
 *     "redis.dimension", 1536           // Dimensão dos vetores
 * );
 * }</pre>
 */
public class RedisVectorStoreAdapter implements LangChainAdapter, EmbeddingStore<TextSegment> {
    private volatile JedisPool jedisPool;
    private String prefix;
    private int dimension;
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
        this.prefix = (String) properties.getOrDefault("redis.prefix", "embedding:");
        this.dimension = (Integer) properties.get("redis.dimension");

        this.jedisPool = new JedisPool(host, port);
    }

    @Override
    public void add(String id, Embedding embedding) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = prefix + id;
            // Converte vetor para string armazenável no Redis
            String vectorStr = vectorToString(embedding.vector());
            jedis.hset(key, "vector", vectorStr);
        }
    }

    @Override
    public String add(Embedding embedding) {
        String id = java.util.UUID.randomUUID().toString();
        add(id, embedding);
        return id;
    }

    @Override
    public String add(Embedding embedding, TextSegment embedded) {
        String id = java.util.UUID.randomUUID().toString();
        add(id, embedding);
        try (Jedis jedis = jedisPool.getResource()) {
            String key = prefix + id;
            jedis.hset(key, "text", embedded.text());
            // Armazena metadados do TextSegment
            if (embedded.metadata() != null && !embedded.metadata().toMap().isEmpty()) {
                Map<String, String> metadata = embedded.metadata().toMap().entrySet().stream()
                    .collect(Collectors.toMap(
                        e -> "metadata." + e.getKey(),
                        e -> String.valueOf(e.getValue())
                    ));
                jedis.hset(key, metadata);
            }
        }
        return id;
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        List<String> ids = new ArrayList<>();
        try (Jedis jedis = jedisPool.getResource()) {
            Pipeline p = jedis.pipelined();
            for (Embedding embedding : embeddings) {
                String id = java.util.UUID.randomUUID().toString();
                ids.add(id);
                String key = prefix + id;
                p.hset(key, "vector", vectorToString(embedding.vector()));
            }
            p.sync();
        }
        return ids;
    }

    @Override
    public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> embedded) {
        try (Jedis jedis = jedisPool.getResource()) {
            Pipeline p = jedis.pipelined();
            for (int i = 0; i < ids.size(); i++) {
                String key = prefix + ids.get(i);
                p.hset(key, "vector", vectorToString(embeddings.get(i).vector()));
                if (embedded.get(i) != null) {
                    p.hset(key, "text", embedded.get(i).text());
                    // Armazena metadados
                    if (embedded.get(i).metadata() != null && !embedded.get(i).metadata().toMap().isEmpty()) {
                        Map<String, String> metadata = embedded.get(i).metadata().toMap().entrySet().stream()
                            .collect(Collectors.toMap(
                                e -> "metadata." + e.getKey(),
                                e -> String.valueOf(e.getValue())
                            ));
                        metadata.forEach((k, v) -> p.hset(key, k, v));
                    }
                }
            }
            p.sync();
        }
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        int maxResults = request.maxResults() > 0 ? request.maxResults() : 10;
        double minScore = request.minScore();

        // Busca todas as chaves com o prefixo usando KEYS
        List<Candidate> candidates = new ArrayList<>();
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> keys = jedis.keys(prefix + "*");

            for (String key : keys) {
                Map<String, String> data = jedis.hgetAll(key);
                String vectorStr = data.get("vector");
                if (vectorStr != null) {
                    float[] vector = stringToVector(vectorStr);
                    double score = cosineSimilarity(request.queryEmbedding().vector(), vector);
                    if (score >= minScore) {
                        String id = key.substring(prefix.length());
                        candidates.add(new Candidate(id, score, data));
                    }
                }
            }
        }

        // Ordena por score (similaridade) descendente e pega os top-k
        List<EmbeddingMatch<TextSegment>> matches = candidates.stream()
            .sorted((a, b) -> Double.compare(b.score, a.score))
            .limit(maxResults)
            .map(c -> {
                TextSegment segment = null;
                if (c.data.containsKey("text")) {
                    segment = TextSegment.from(c.data.get("text"));
                }
                // EmbeddingMatch constructor: (Double score, String embeddingId, Embedding embedding, Embedded embedded)
                return new EmbeddingMatch<>(
                    c.score,
                    c.id,
                    request.queryEmbedding(),
                    segment
                );
            })
            .collect(Collectors.toList());

        return new EmbeddingSearchResult<>(matches);
    }

    @Override
    public Object execute(String operation, Object input, ExecutionContext context) throws Exception {
        if (jedisPool == null) {
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
        if (jedisPool != null) {
            jedisPool.close();
            jedisPool = null;
        }
        this.config = null;
    }

    /**
     * Calcula a similaridade de cosseno entre dois vetores.
     * Cosine Similarity = (A . B) / (||A|| * ||B||)
     */
    private double cosineSimilarity(float[] vectorA, float[] vectorB) {
        if (vectorA.length != vectorB.length) {
            throw new IllegalArgumentException("Vector dimensions must match");
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < vectorA.length; i++) {
            dotProduct += vectorA[i] * vectorB[i];
            normA += vectorA[i] * vectorA[i];
            normB += vectorB[i] * vectorB[i];
        }

        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private String vectorToString(float[] vector) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vector[i]);
        }
        return sb.toString();
    }

    private float[] stringToVector(String str) {
        String[] parts = str.split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Float.parseFloat(parts[i]);
        }
        return vector;
    }

    /**
     * Candidato interno para armazenar resultados da busca.
     */
    private static class Candidate {
        final String id;
        final double score;
        final Map<String, String> data;

        Candidate(String id, double score, Map<String, String> data) {
            this.id = id;
            this.score = score;
            this.data = data;
        }
    }

    public static class Factory implements LangChainAdapterFactory {
        @Override
        public String getProvider() {
            return "redis";
        }

        @Override
        public LangChainAdapter createAdapter(Map<String, Object> properties) {
            RedisVectorStoreAdapter adapter = new RedisVectorStoreAdapter();
            adapter.configure(properties);
            return adapter;
        }

        @Override
        public boolean supports(String type) {
            return "vectorstore".equals(type);
        }
    }
}

