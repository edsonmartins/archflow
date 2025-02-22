package br.com.archflow.langchain4j.vectorstore.redis;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.langchain4j.core.spi.LangChainAdapterFactory;
import br.com.archflow.model.engine.ExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.commands.ProtocolCommand;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.util.SafeEncoder;

import java.util.*;

/**
 * Adapter para armazenamento e busca de embeddings usando Redis.
 * Utiliza Redis Stack com RediSearch e RedisJSON para vetores.
 *
 * <p>Este adapter requer Redis Stack com os módulos RedisJSON e RediSearch.
 *
 * <p>Exemplo de configuração:
 * <pre>{@code
 * Map<String, Object> config = Map.of(
 *     "redis.host", "localhost",         // Host do Redis
 *     "redis.port", 6379,               // Porta do Redis
 *     "redis.index", "embeddings",      // Nome do índice (opcional)
 *     "redis.dimension", 1536,          // Dimensão dos vetores
 *     "redis.prefix", "doc:",           // Prefixo das chaves (opcional)
 *     "redis.pool.maxTotal", 8,         // Máximo de conexões (opcional)
 *     "redis.pool.maxIdle", 8,          // Máximo de conexões ociosas (opcional)
 *     "redis.pool.minIdle", 0           // Mínimo de conexões ociosas (opcional)
 * );
 * }</pre>
 */
public class RedisVectorStoreAdapter implements LangChainAdapter, EmbeddingStore<TextSegment>, AutoCloseable {
    private static final String FT_CREATE = "FT.CREATE";
    private static final String FT_SEARCH = "FT.SEARCH";
    private static final String FT_INFO = "FT.INFO";
    private static final String JSON_SET = "JSON.SET";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private volatile JedisPool jedisPool;
    private String indexName;
    private String keyPrefix;
    private int vectorDimension;
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

        String host = (String) properties.get("redis.host");
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("Redis host is required");
        }

        Object port = properties.get("redis.port");
        if (port != null && (!(port instanceof Number) || ((Number) port).intValue() < 1 || ((Number) port).intValue() > 65535)) {
            throw new IllegalArgumentException("Redis port must be a number between 1 and 65535");
        }

        String indexName = (String) properties.get("redis.index");
        if (indexName != null && indexName.trim().isEmpty()) {
            throw new IllegalArgumentException("Redis index name cannot be empty if provided");
        }

        Object dimension = properties.get("redis.dimension");
        if (dimension == null || !(dimension instanceof Number) || ((Number) dimension).intValue() <= 0) {
            throw new IllegalArgumentException("Vector dimension is required and must be a positive number");
        }

        Object maxTotal = properties.get("redis.pool.maxTotal");
        if (maxTotal != null && (!(maxTotal instanceof Number) || ((Number) maxTotal).intValue() < 1)) {
            throw new IllegalArgumentException("redis.pool.maxTotal must be a positive number");
        }
    }

    /**
     * Configura o adapter com as propriedades especificadas.
     *
     * @param properties Map com as configurações
     * @throws IllegalArgumentException se as configurações forem inválidas
     */
    @Override
    public void configure(Map<String, Object> properties) {
        validate(properties);
        this.config = properties;

        String host = (String) properties.get("redis.host");
        int port = (Integer) properties.getOrDefault("redis.port", 6379);
        this.indexName = (String) properties.getOrDefault("redis.index", "embeddings");
        this.keyPrefix = (String) properties.getOrDefault("redis.prefix", "doc:");
        this.vectorDimension = (Integer) properties.get("redis.dimension");

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal((Integer) properties.getOrDefault("redis.pool.maxTotal", 8));
        poolConfig.setMaxIdle((Integer) properties.getOrDefault("redis.pool.maxIdle", 8));
        poolConfig.setMinIdle((Integer) properties.getOrDefault("redis.pool.minIdle", 0));
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestOnReturn(true);

        this.jedisPool = new JedisPool(poolConfig, host, port);

        createIndexIfNotExists();
    }

    private void createIndexIfNotExists() {
        try (var jedis = jedisPool.getResource()) {
            try {
                jedis.sendCommand(new Command(FT_INFO), SafeEncoder.encode(indexName));
            } catch (Exception e) {
                String createIndexCommand = String.format(
                        "FT.CREATE %s ON JSON PREFIX 1 %s SCHEMA $.embedding AS embedding VECTOR HNSW 6 TYPE FLOAT32 DIM %d DISTANCE_METRIC COSINE $.text AS text TEXT",
                        indexName, keyPrefix, vectorDimension
                );

                jedis.sendCommand(new Command(FT_CREATE),
                        Arrays.stream(createIndexCommand.split(" "))
                                .map(SafeEncoder::encode)
                                .toArray(byte[][]::new));
            }
        } catch (JedisConnectionException e) {
            throw new RuntimeException("Failed to connect to Redis while creating index", e);
        }
    }

    @Override
    public void add(String id, Embedding embedding) {
        try (var jedis = jedisPool.getResource()) {
            Map<String, Object> doc = new HashMap<>();
            doc.put("embedding", embedding.vector());

            String key = keyPrefix + id;
            String json = objectMapper.writeValueAsString(doc);

            jedis.sendCommand(new Command(JSON_SET),
                    SafeEncoder.encode(key),
                    SafeEncoder.encode("$"),
                    SafeEncoder.encode(json));
        } catch (JedisConnectionException e) {
            throw new RuntimeException("Failed to connect to Redis while adding embedding", e);
        } catch (Exception e) {
            throw new RuntimeException("Error adding embedding to Redis", e);
        }
    }

    @Override
    public String add(Embedding embedding) {
        return add(embedding, null);
    }

    @Override
    public String add(Embedding embedding, TextSegment embedded) {
        String id = UUID.randomUUID().toString();
        try (var jedis = jedisPool.getResource()) {
            Map<String, Object> doc = new HashMap<>();
            doc.put("embedding", embedding.vector());
            if (embedded != null) {
                doc.put("text", embedded.text());
            }

            String key = keyPrefix + id;
            String json = objectMapper.writeValueAsString(doc);

            jedis.sendCommand(new Command(JSON_SET),
                    SafeEncoder.encode(key),
                    SafeEncoder.encode("$"),
                    SafeEncoder.encode(json));
        } catch (JedisConnectionException e) {
            throw new RuntimeException("Failed to connect to Redis while adding embedding", e);
        } catch (Exception e) {
            throw new RuntimeException("Error adding embedding to Redis", e);
        }
        return id;
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        List<String> ids = generateIds(embeddings.size());
        addAll(ids, embeddings, null);
        return ids;
    }

    @Override
    public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> embedded) {
        if (ids.size() != embeddings.size() || (embedded != null && embedded.size() != ids.size())) {
            throw new IllegalArgumentException("All lists must have the same size");
        }

        try (var jedis = jedisPool.getResource()) {
            var pipeline = jedis.pipelined();
            for (int i = 0; i < ids.size(); i++) {
                Map<String, Object> doc = new HashMap<>();
                doc.put("embedding", embeddings.get(i).vector());
                if (embedded != null) {
                    doc.put("text", embedded.get(i).text());
                }

                String key = keyPrefix + ids.get(i);
                String json = objectMapper.writeValueAsString(doc);

                pipeline.sendCommand(new Command(JSON_SET),
                        SafeEncoder.encode(key),
                        SafeEncoder.encode("$"),
                        SafeEncoder.encode(json));
            }
            pipeline.sync();
        } catch (JedisConnectionException e) {
            throw new RuntimeException("Failed to connect to Redis while adding embeddings batch", e);
        } catch (Exception e) {
            throw new RuntimeException("Error adding embeddings batch to Redis", e);
        }
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        try (var jedis = jedisPool.getResource()) {
            StringBuilder queryBuilder = new StringBuilder();
            queryBuilder.append(String.format("*=>[KNN %d @embedding $embedding", request.maxResults()));

            double minScore = request.minScore();
            if (minScore > 0) {
                queryBuilder.append(" FILTER score >= ").append(String.format("%.2f", minScore));
            }
            queryBuilder.append("]");

            float[] vector = request.queryEmbedding().vector();
            StringBuilder vectorJson = new StringBuilder("[");
            for (int i = 0; i < vector.length; i++) {
                vectorJson.append(i > 0 ? "," : "").append(vector[i]);
            }
            vectorJson.append("]");

            Object rawResult = jedis.sendCommand(
                    new Command(FT_SEARCH),
                    SafeEncoder.encode(indexName),
                    SafeEncoder.encode(queryBuilder.toString()),
                    SafeEncoder.encode("PARAMS"), SafeEncoder.encode("2"),
                    SafeEncoder.encode("embedding"), SafeEncoder.encode(vectorJson.toString()),
                    SafeEncoder.encode("RETURN"), SafeEncoder.encode("3"),
                    SafeEncoder.encode("text"), SafeEncoder.encode("score"), SafeEncoder.encode("embedding")
            );

            List<EmbeddingMatch<TextSegment>> matches = parseSearchResults((Object[]) rawResult);
            return new EmbeddingSearchResult<>(matches);
        } catch (JedisConnectionException e) {
            throw new RuntimeException("Failed to connect to Redis while searching embeddings", e);
        } catch (Exception e) {
            throw new RuntimeException("Error searching embeddings in Redis", e);
        }
    }

    private List<EmbeddingMatch<TextSegment>> parseSearchResults(Object[] rawResult) {
        List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();
        if (rawResult == null || rawResult.length < 1) {
            return matches;
        }

        for (int i = 1; i < rawResult.length; i += 2) {
            String id = new String((byte[]) rawResult[i]);
            Object[] fields = (Object[]) rawResult[i + 1];

            Map<String, String> document = new HashMap<>();
            for (int j = 0; j < fields.length; j += 2) {
                String fieldName = new String((byte[]) fields[j]);
                String fieldValue = new String((byte[]) fields[j + 1]);
                document.put(fieldName, fieldValue);
            }

            double score = Double.parseDouble(document.getOrDefault("score", "0.0"));
            String text = document.get("text");
            String embeddingJson = document.get("embedding");
            String embeddingId = id.replace(keyPrefix, "");

            Embedding embedding = null;
            if (embeddingJson != null) {
                try {
                    float[] vector = objectMapper.readValue(embeddingJson, float[].class);
                    embedding = new Embedding(vector);
                } catch (Exception e) {
                    // Logar o erro se houver sistema de log disponível
                }
            }

            matches.add(new EmbeddingMatch<>(score, embeddingId, embedding, text != null ? TextSegment.from(text) : null));
        }
        return matches;
    }

    /**
     * Executa operações no vector store do Redis.
     *
     * @param operation Nome da operação ("search" é suportado)
     * @param input     Para "search": {@link EmbeddingSearchRequest}
     * @param context   Contexto de execução (não utilizado atualmente)
     * @return Resultado da operação
     * @throws IllegalArgumentException se a operação for inválida
     * @throws IllegalStateException    se o adapter não estiver configurado
     */
    @Override
    public synchronized Object execute(String operation, Object input, ExecutionContext context) throws Exception {
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

    /**
     * Libera recursos utilizados pelo adapter, fechando o pool de conexões ao Redis.
     */
    @Override
    public void shutdown() {
        if (jedisPool != null) {
            jedisPool.close();
            jedisPool = null;
        }
        this.config = null;
    }

    @Override
    public void close() {
        shutdown();
    }

    public List<String> generateIds(int count) {
        List<String> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(UUID.randomUUID().toString());
        }
        return ids;
    }

    private static class Command implements ProtocolCommand {
        private final String name;

        public Command(String name) {
            this.name = name;
        }

        @Override
        public byte[] getRaw() {
            return SafeEncoder.encode(name);
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