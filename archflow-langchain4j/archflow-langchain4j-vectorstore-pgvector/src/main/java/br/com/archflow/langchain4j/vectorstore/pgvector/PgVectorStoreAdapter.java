package br.com.archflow.langchain4j.vectorstore.pgvector;

import br.com.archflow.langchain4j.core.filter.MetadataFilterEvaluator;
import br.com.archflow.langchain4j.core.filter.MetadataJsonCodec;
import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.langchain4j.core.spi.LangChainAdapterFactory;
import br.com.archflow.model.engine.ExecutionContext;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.filter.Filter;

import java.sql.*;
import java.util.*;

/**
 * Adapter para armazenamento e busca de embeddings usando PgVector no PostgreSQL.
 * Suporta filtros por metadados e remoção de embeddings.
 *
 * <p><b>Metadados:</b> os metadados do {@link TextSegment} são persistidos na
 * coluna {@code metadata} (TEXT, JSON serializado — portável, sem dependência
 * de JSONB).
 *
 * <p><b>Filtros ({@code request.filter()}):</b> a abordagem escolhida é buscar
 * os top {@code maxResults * } {@link #FILTER_OVERSAMPLING_FACTOR} candidatos
 * por similaridade <b>sem</b> filtro no SQL e aplicar o {@link Filter} em
 * memória sobre os metadados desserializados de cada candidato (correto e
 * portável; nunca ignora o filtro silenciosamente). Limitação documentada: se
 * os itens que satisfazem o filtro forem mais raros que
 * {@code 1/FILTER_OVERSAMPLING_FACTOR} dentro do top-K ampliado, o resultado
 * pode conter menos itens que {@code maxResults} mesmo existindo mais matches
 * na base. Tipos de filtro não suportados lançam
 * {@link UnsupportedOperationException} clara.
 *
 * <p>Exemplo de configuração:
 * <pre>{@code
 * Map<String, Object> config = Map.of(
 *     "pgvector.jdbcUrl", "jdbc:postgresql://localhost:5432/archflow", // URL do banco
 *     "pgvector.username", "postgres",                                 // Usuário do banco
 *     "pgvector.password", "password",                                 // Senha do banco
 *     "pgvector.table", "embeddings",                                  // Nome da tabela (opcional)
 *     "pgvector.dimension", 1536                                       // Dimensão dos vetores
 * );
 * }</pre>
 */
public class PgVectorStoreAdapter implements LangChainAdapter, dev.langchain4j.store.embedding.EmbeddingStore<TextSegment>, AutoCloseable {

    /**
     * Strict whitelist for valid PostgreSQL identifier characters. Since
     * table names are spliced into SQL via {@code String.format("%s",...)}
     * (JDBC does not parameterize identifiers), any user-controlled value
     * here must be validated up front to prevent SQL injection.
     * Lowercase/uppercase letters, digits, and underscore only; must not
     * start with a digit. Schema-qualified names (e.g. {@code foo.bar})
     * are NOT accepted.
     */
    private static final java.util.regex.Pattern VALID_TABLE_NAME =
            java.util.regex.Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,62}$");

    /**
     * Quando há {@code request.filter()}, o SQL busca
     * {@code maxResults * FILTER_OVERSAMPLING_FACTOR} candidatos por
     * similaridade e o filtro é aplicado em memória sobre os metadados.
     * Ver javadoc da classe para a limitação de recall associada.
     */
    static final int FILTER_OVERSAMPLING_FACTOR = 10;

    private volatile HikariDataSource dataSource;
    private String tableName;
    private int dimension;
    private Map<String, Object> config;

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
        if (tableName != null) {
            if (tableName.trim().isEmpty()) {
                throw new IllegalArgumentException("PgVector table name cannot be empty if provided");
            }
            if (!VALID_TABLE_NAME.matcher(tableName).matches()) {
                throw new IllegalArgumentException(
                        "PgVector table name must match " + VALID_TABLE_NAME.pattern()
                                + " (letters, digits, underscore; max 63 chars; cannot start with a digit). "
                                + "Got: " + tableName);
            }
        }

        Object dimension = properties.get("pgvector.dimension");
        if (dimension == null || !(dimension instanceof Number) || ((Number) dimension).intValue() <= 0) {
            throw new IllegalArgumentException("Vector dimension is required and must be a positive number");
        }
    }

    @Override
    public void configure(Map<String, Object> properties) {
        validate(properties);
        this.config = properties;

        String jdbcUrl = (String) properties.get("pgvector.jdbcUrl");
        String username = (String) properties.get("pgvector.username");
        String password = (String) properties.get("pgvector.password");
        this.tableName = (String) properties.getOrDefault("pgvector.table", "embeddings");
        this.dimension = (Integer) properties.get("pgvector.dimension");

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
            stmt.execute("CREATE EXTENSION IF NOT EXISTS vector");
            String createTableSql = String.format(
                    "CREATE TABLE IF NOT EXISTS %s ("
                            + "id VARCHAR(36) PRIMARY KEY, "
                            + "embedding vector(%d), "
                            + "text TEXT, "
                            + "metadata TEXT)",
                    tableName, dimension
            );
            stmt.execute(createTableSql);
            // Migração leve para tabelas criadas por versões antigas (sem metadata)
            stmt.execute(String.format(
                    "ALTER TABLE %s ADD COLUMN IF NOT EXISTS metadata TEXT", tableName));
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
                     String.format("INSERT INTO %s (id, embedding, text, metadata) VALUES (?, ?::vector, ?, ?) ON CONFLICT (id) DO UPDATE SET embedding = EXCLUDED.embedding, text = EXCLUDED.text, metadata = EXCLUDED.metadata", tableName))) {
            stmt.setString(1, id);
            stmt.setString(2, vectorToString(embedding.vector()));
            stmt.setString(3, embedded != null ? embedded.text() : null);
            stmt.setString(4, embedded != null ? MetadataJsonCodec.toJson(embedded.metadata()) : null);
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
                     String.format("INSERT INTO %s (id, embedding, text, metadata) VALUES (?, ?::vector, ?, ?) ON CONFLICT (id) DO UPDATE SET embedding = EXCLUDED.embedding, text = EXCLUDED.text, metadata = EXCLUDED.metadata", tableName))) {
            conn.setAutoCommit(false);
            for (int i = 0; i < ids.size(); i++) {
                TextSegment segment = embedded != null ? embedded.get(i) : null;
                stmt.setString(1, ids.get(i));
                stmt.setString(2, vectorToString(embeddings.get(i).vector()));
                stmt.setString(3, segment != null ? segment.text() : null);
                stmt.setString(4, segment != null ? MetadataJsonCodec.toJson(segment.metadata()) : null);
                stmt.addBatch();
            }
            stmt.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            throw new RuntimeException("Error adding embeddings batch to PgVector", e);
        }
    }

    /**
     * Busca por similaridade (distância de cosseno via {@code <=>}).
     *
     * <p>Quando {@code request.filter()} está presente, o SQL busca
     * {@code maxResults * } {@link #FILTER_OVERSAMPLING_FACTOR} candidatos sem
     * filtro e o {@link Filter} é aplicado em memória sobre os metadados
     * desserializados (JSON da coluna {@code metadata}) — ver javadoc da
     * classe para a abordagem escolhida e sua limitação de recall.
     *
     * @throws UnsupportedOperationException se o filtro contiver um tipo não suportado
     */
    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        Filter filter = request.filter();
        // Falha cedo (antes do SQL) se o filtro tiver tipo não suportado
        MetadataFilterEvaluator.ensureSupported(filter);

        int maxResults = request.maxResults();
        boolean oversampling = filter != null;
        int fetchLimit = oversampling
                ? (int) Math.min((long) maxResults * FILTER_OVERSAMPLING_FACTOR, Integer.MAX_VALUE)
                : maxResults;

        // No caminho com filtro buscamos maxResults*10 candidatos e descartamos
        // a maioria em memória — trazer a coluna `embedding` (o vetor inteiro,
        // ~KBs por linha) de todos eles é desperdício puro. Omite o vetor nesse
        // caminho (EmbeddingMatch aceita embedding nulo); no caminho exato (sem
        // filtro) todo candidato retorna, então o vetor é incluído.
        String columns = oversampling ? "id, text, metadata" : "id, embedding, text, metadata";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     String.format("SELECT %s, embedding <=> ?::vector AS distance FROM %s ORDER BY distance LIMIT ?",
                             columns, tableName))) {
            stmt.setString(1, vectorToString(request.queryEmbedding().vector()));
            stmt.setInt(2, fetchLimit);

            ResultSet rs = stmt.executeQuery();
            List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();

            while (rs.next() && matches.size() < maxResults) {
                String id = rs.getString("id");
                String text = rs.getString("text");
                String metadataJson = rs.getString("metadata");
                double distance = rs.getDouble("distance");
                double score = 1 - distance; // Converter distância para similaridade

                if (score < request.minScore()) {
                    continue;
                }

                Metadata metadata = MetadataJsonCodec.fromJson(metadataJson);
                // Aplica o filtro em memória — nunca ignorado silenciosamente
                if (!MetadataFilterEvaluator.matches(filter, metadata)) {
                    continue;
                }

                Embedding embedding = oversampling
                        ? null
                        : new Embedding(parseVector(rs.getString("embedding")));
                TextSegment textSegment = text != null ? TextSegment.from(text, metadata) : null;
                matches.add(new EmbeddingMatch<>(score, id, embedding, textSegment));
            }

            return new EmbeddingSearchResult<>(matches);
        } catch (SQLException e) {
            throw new RuntimeException("Error searching embeddings in PgVector", e);
        }
    }

    // Métodos de remoção
    public void remove(String id) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     String.format("DELETE FROM %s WHERE id = ?", tableName))) {
            stmt.setString(1, id);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Error removing embedding from PgVector", e);
        }
    }

    public void removeAll(List<String> ids) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     String.format("DELETE FROM %s WHERE id = ANY(?)", tableName))) {
            Array array = conn.createArrayOf("varchar", ids.toArray());
            stmt.setArray(1, array);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Error removing embeddings from PgVector", e);
        }
    }

    public void removeAll() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(String.format("DELETE FROM %s", tableName));
        } catch (SQLException e) {
            throw new RuntimeException("Error removing all embeddings from PgVector", e);
        }
    }

    @Override
    public Object execute(String operation, Object input, ExecutionContext context) throws Exception {
        if (dataSource == null) {
            throw new IllegalStateException("Vector store not configured. Call configure() first.");
        }

        switch (operation) {
            case "search":
                if (!(input instanceof EmbeddingSearchRequest)) {
                    throw new IllegalArgumentException("Input must be an EmbeddingSearchRequest for search operation");
                }
                return search((EmbeddingSearchRequest) input);
            case "remove":
                if (!(input instanceof String)) {
                    throw new IllegalArgumentException("Input must be a String ID for remove operation");
                }
                remove((String) input);
                return null;
            case "removeAll":
                if (input instanceof List) {
                    removeAll((List<String>) input);
                } else if (input == null) {
                    removeAll();
                } else {
                    throw new IllegalArgumentException("Input must be a List of IDs or null for removeAll operation");
                }
                return null;
            default:
                throw new IllegalArgumentException("Unsupported operation: " + operation);
        }
    }

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


}