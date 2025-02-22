package br.com.archflow.langchain4j.vectorstore.pgvector;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.langchain4j.core.spi.LangChainAdapterFactory;
import br.com.archflow.model.engine.ExecutionContext;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;

import java.sql.*;
import java.util.*;

/**
 * Adapter para armazenamento e busca de embeddings usando PgVector no PostgreSQL.
 * Utiliza a extensão PgVector para suportar vetores de alta dimensão e busca por similaridade.
 *
 * <p>Este adapter requer um banco PostgreSQL com a extensão PgVector instalada.
 *
 * <p>Exemplo de configuração:
 * <pre>{@code
 * Map<String, Object> config = Map.of(
 *     "pgvector.jdbcUrl", "jdbc:postgresql://localhost:5432/archflow", // URL do banco
 *     "pgvector.username", "postgres",                                 // Usuário do banco
 *     "pgvector.password", "password",                                 // Senha do banco
 *     "pgvector.table", "embeddings",                                  // Nome da tabela (opcional)
 *     "pgvector.dimension", 1536,                                      // Dimensão dos vetores
 *     "pgvector.pool.maxSize", 8                                       // Tamanho máximo do pool (opcional)
 * );
 * }</pre>
 */
public class PgVectorStoreAdapter implements LangChainAdapter, EmbeddingStore<TextSegment>, AutoCloseable {
    private volatile HikariDataSource dataSource;
    private String tableName;
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

        String jdbcUrl = (String) properties.get("pgvector.jdbcUrl");
        if (jdbcUrl == null || jdbcUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("PgVector JDBC URL is required");
        }

        String username = (String) properties.get("pgvector.username");
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("PgVector username is required");
        }

        String password = (String) properties.get("pgvector.password");
        if (password == null) {
            throw new IllegalArgumentException("PgVector password is required");
        }

        String tableName = (String) properties.get("pgvector.table");
        if (tableName != null && tableName.trim().isEmpty()) {
            throw new IllegalArgumentException("PgVector table name cannot be empty if provided");
        }

        Object dimension = properties.get("pgvector.dimension");
        if (dimension == null || !(dimension instanceof Number) || ((Number) dimension).intValue() <= 0) {
            throw new IllegalArgumentException("Vector dimension is required and must be a positive number");
        }

        Object maxSize = properties.get("pgvector.pool.maxSize");
        if (maxSize != null && (!(maxSize instanceof Number) || ((Number) maxSize).intValue() < 1)) {
            throw new IllegalArgumentException("pgvector.pool.maxSize must be a positive number");
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

        String jdbcUrl = (String) properties.get("pgvector.jdbcUrl");
        String username = (String) properties.get("pgvector.username");
        String password = (String) properties.get("pgvector.password");
        this.tableName = (String) properties.getOrDefault("pgvector.table", "embeddings");
        this.vectorDimension = (Integer) properties.get("pgvector.dimension");

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setUsername(username);
        hikariConfig.setPassword(password);
        hikariConfig.setMaximumPoolSize((Integer) properties.getOrDefault("pgvector.pool.maxSize", 8));
        hikariConfig.setMinimumIdle((Integer) properties.getOrDefault("pgvector.pool.minIdle", 0));
        hikariConfig.setDriverClassName("org.postgresql.Driver");

        this.dataSource = new HikariDataSource(hikariConfig);

        createTableIfNotExists();
    }

    private void createTableIfNotExists() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            // Cria a extensão PgVector, se não existir
            stmt.execute("CREATE EXTENSION IF NOT EXISTS vector");

            // Cria a tabela, se não existir
            String createTableSql = String.format(
                    "CREATE TABLE IF NOT EXISTS %s ("
                            + "id VARCHAR(36) PRIMARY KEY, "
                            + "embedding vector(%d), "
                            + "text TEXT)",
                    tableName, vectorDimension
            );
            stmt.execute(createTableSql);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize PgVector table", e);
        }
    }

    @Override
    public void add(String id, Embedding embedding) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     String.format("INSERT INTO %s (id, embedding) VALUES (?, ?::vector) ON CONFLICT (id) DO UPDATE SET embedding = EXCLUDED.embedding", tableName))) {
            stmt.setString(1, id);
            stmt.setString(2, vectorToString(embedding.vector()));
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Error adding embedding to PgVector", e);
        }
    }

    @Override
    public String add(Embedding embedding) {
        return add(embedding, null);
    }

    @Override
    public String add(Embedding embedding, TextSegment embedded) {
        String id = UUID.randomUUID().toString();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     String.format("INSERT INTO %s (id, embedding, text) VALUES (?, ?::vector, ?) ON CONFLICT (id) DO UPDATE SET embedding = EXCLUDED.embedding, text = EXCLUDED.text", tableName))) {
            stmt.setString(1, id);
            stmt.setString(2, vectorToString(embedding.vector()));
            stmt.setString(3, embedded != null ? embedded.text() : null);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Error adding embedding to PgVector", e);
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

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     String.format("INSERT INTO %s (id, embedding, text) VALUES (?, ?::vector, ?) ON CONFLICT (id) DO UPDATE SET embedding = EXCLUDED.embedding, text = EXCLUDED.text", tableName))) {
            conn.setAutoCommit(false);
            for (int i = 0; i < ids.size(); i++) {
                stmt.setString(1, ids.get(i));
                stmt.setString(2, vectorToString(embeddings.get(i).vector()));
                stmt.setString(3, embedded != null ? embedded.get(i).text() : null);
                stmt.addBatch();
            }
            stmt.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            throw new RuntimeException("Error adding embeddings batch to PgVector", e);
        }
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     String.format("SELECT id, embedding, text, embedding <=> ?::vector AS distance FROM %s ORDER BY distance LIMIT ?", tableName))) {
            stmt.setString(1, vectorToString(request.queryEmbedding().vector()));
            stmt.setInt(2, request.maxResults());

            ResultSet rs = stmt.executeQuery();
            List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();

            while (rs.next()) {
                String id = rs.getString("id");
                String embeddingStr = rs.getString("embedding");
                String text = rs.getString("text");
                double distance = rs.getDouble("distance");
                double score = 1 - distance; // Converter distância para similaridade (cosseno)

                if (score >= request.minScore()) {
                    float[] vector = parseVector(embeddingStr);
                    Embedding embedding = new Embedding(vector);
                    TextSegment textSegment = text != null ? TextSegment.from(text) : null;
                    matches.add(new EmbeddingMatch<>(score, id, embedding, textSegment));
                }
            }

            return new EmbeddingSearchResult<>(matches);
        } catch (SQLException e) {
            throw new RuntimeException("Error searching embeddings in PgVector", e);
        }
    }

    /**
     * Executa operações no vector store do PgVector.
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
        if (dataSource == null) {
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
     * Libera recursos utilizados pelo adapter, fechando o pool de conexões ao PostgreSQL.
     */
    @Override
    public void shutdown() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
        this.config = null;
    }

    @Override
    public void close() {
        shutdown();
    }

    private String vectorToString(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            sb.append(i > 0 ? "," : "").append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    private float[] parseVector(String vectorStr) {
        String[] parts = vectorStr.substring(1, vectorStr.length() - 1).split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Float.parseFloat(parts[i].trim());
        }
        return vector;
    }

    public List<String> generateIds(int count) {
        List<String> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(UUID.randomUUID().toString());
        }
        return ids;
    }

    public static class Factory implements LangChainAdapterFactory {
        @Override
        public String getProvider() {
            return "pgvector";
        }

        @Override
        public LangChainAdapter createAdapter(Map<String, Object> properties) {
            PgVectorStoreAdapter adapter = new PgVectorStoreAdapter();
            adapter.configure(properties);
            return adapter;
        }

        @Override
        public boolean supports(String type) {
            return "vectorstore".equals(type);
        }
    }
}