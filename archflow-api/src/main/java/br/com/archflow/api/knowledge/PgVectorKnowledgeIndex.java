package br.com.archflow.api.knowledge;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Tenant-filtered, versioned pgvector index sharing the application's managed DataSource. */
public class PgVectorKnowledgeIndex implements KnowledgeVectorIndex {
    private final DataSource dataSource;
    private final int dimension;

    public PgVectorKnowledgeIndex(DataSource dataSource, int dimension) {
        this.dataSource = dataSource;
        this.dimension = dimension;
    }

    public IndexVersion createCandidate(String tenantId, String baseId) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                IndexVersion active = activeVersion(connection, tenantId, baseId, false);
                String id = "idx-" + UUID.randomUUID();
                Instant createdAt = Instant.now();
                try (var insert = connection.prepareStatement("""
                        INSERT INTO knowledge_index_versions
                            (id, tenant_id, knowledge_base_id, based_on_version_id,
                             status, created_at)
                        VALUES (?, ?, ?, ?, 'BUILDING', ?)
                        """)) {
                    insert.setString(1, id);
                    insert.setString(2, tenantId);
                    insert.setString(3, baseId);
                    insert.setString(4, active == null ? null : active.id());
                    insert.setObject(5, createdAt);
                    insert.executeUpdate();
                }
                if (active != null) {
                    try (var copy = connection.prepareStatement("""
                            INSERT INTO knowledge_embeddings
                                (index_version_id, chunk_id, tenant_id, knowledge_base_id,
                                 document_id, content, embedding)
                            SELECT ?, chunk_id, tenant_id, knowledge_base_id,
                                   document_id, content, embedding
                              FROM knowledge_embeddings
                             WHERE tenant_id=? AND knowledge_base_id=?
                               AND index_version_id=?
                            """)) {
                        copy.setString(1, id);
                        copy.setString(2, tenantId);
                        copy.setString(3, baseId);
                        copy.setString(4, active.id());
                        copy.executeUpdate();
                    }
                }
                connection.commit();
                return new IndexVersion(id, baseId, active == null ? null : active.id(),
                        "BUILDING", createdAt, null);
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to create pgvector index candidate", e);
        }
    }

    public void replaceDocument(String tenantId, String baseId, String versionId,
                                String documentId, List<EmbeddedChunk> chunks) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var delete = connection.prepareStatement("""
                        DELETE FROM knowledge_embeddings
                         WHERE tenant_id=? AND knowledge_base_id=?
                           AND index_version_id=? AND document_id=?
                        """)) {
                    delete.setString(1, tenantId);
                    delete.setString(2, baseId);
                    delete.setString(3, versionId);
                    delete.setString(4, documentId);
                    delete.executeUpdate();
                }
                try (var insert = connection.prepareStatement("""
                        INSERT INTO knowledge_embeddings
                            (index_version_id, chunk_id, tenant_id, knowledge_base_id,
                             document_id, content, embedding)
                        VALUES (?, ?, ?, ?, ?, ?, ?::vector)
                        """)) {
                    for (EmbeddedChunk item : chunks) {
                        requireDimension(item.embedding());
                        insert.setString(1, versionId);
                        insert.setString(2, item.chunk().id());
                        insert.setString(3, tenantId);
                        insert.setString(4, baseId);
                        insert.setString(5, documentId);
                        insert.setString(6, item.chunk().content());
                        insert.setString(7, vector(item.embedding()));
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                connection.commit();
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to replace pgvector document index", e);
        }
    }

    public boolean activate(String tenantId, String baseId, String versionId) {
        return changeActive(tenantId, baseId, versionId, true);
    }

    public boolean rollback(String tenantId, String baseId, String versionId) {
        return changeActive(tenantId, baseId, versionId, false);
    }

    private boolean changeActive(String tenantId, String baseId, String versionId,
                                 boolean optimistic) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                lockBase(connection, tenantId, baseId);
                IndexVersion target = version(connection, tenantId, baseId, versionId, true);
                IndexVersion active = activeVersion(connection, tenantId, baseId, true);
                boolean allowed = target != null
                        && (optimistic
                            ? "BUILDING".equals(target.status())
                                && Objects.equals(target.basedOnVersionId(),
                                                  active == null ? null : active.id())
                            : !"BUILDING".equals(target.status()));
                if (!allowed) {
                    connection.rollback();
                    return false;
                }
                try (var deactivate = connection.prepareStatement("""
                        UPDATE knowledge_index_versions
                           SET status='INACTIVE'
                         WHERE tenant_id=? AND knowledge_base_id=? AND status='ACTIVE'
                        """)) {
                    deactivate.setString(1, tenantId);
                    deactivate.setString(2, baseId);
                    deactivate.executeUpdate();
                }
                try (var activate = connection.prepareStatement("""
                        UPDATE knowledge_index_versions
                           SET status='ACTIVE', activated_at=?
                         WHERE id=? AND tenant_id=? AND knowledge_base_id=?
                        """)) {
                    activate.setObject(1, Instant.now());
                    activate.setString(2, versionId);
                    activate.setString(3, tenantId);
                    activate.setString(4, baseId);
                    activate.executeUpdate();
                }
                connection.commit();
                return true;
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to activate pgvector index version", e);
        }
    }

    public IndexVersion activeVersion(String tenantId, String baseId) {
        try (var connection = dataSource.getConnection()) {
            return activeVersion(connection, tenantId, baseId, false);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load active pgvector index version", e);
        }
    }

    public List<IndexVersion> versions(String tenantId, String baseId) {
        List<IndexVersion> result = new ArrayList<>();
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT id, knowledge_base_id, based_on_version_id, status,
                            created_at, activated_at
                       FROM knowledge_index_versions
                      WHERE tenant_id=? AND knowledge_base_id=?
                      ORDER BY created_at DESC
                     """)) {
            statement.setString(1, tenantId);
            statement.setString(2, baseId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) result.add(mapVersion(rows));
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to list pgvector index versions", e);
        }
    }

    public List<SearchHit> search(String tenantId, String baseId, String versionId,
                                  float[] query, int limit, double minScore) {
        requireDimension(query);
        IndexVersion selected = versionId == null
                ? activeVersion(tenantId, baseId)
                : findVersion(tenantId, baseId, versionId);
        if (selected == null) return List.of();
        List<SearchHit> hits = new ArrayList<>();
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT chunk_id, document_id, content,
                            1 - (embedding <=> ?::vector) AS score
                       FROM knowledge_embeddings
                      WHERE tenant_id=? AND knowledge_base_id=? AND index_version_id=?
                        AND 1 - (embedding <=> ?::vector) >= ?
                      ORDER BY embedding <=> ?::vector
                      LIMIT ?
                     """)) {
            String vector = vector(query);
            statement.setString(1, vector);
            statement.setString(2, tenantId);
            statement.setString(3, baseId);
            statement.setString(4, selected.id());
            statement.setString(5, vector);
            statement.setDouble(6, minScore);
            statement.setString(7, vector);
            statement.setInt(8, Math.max(1, Math.min(100, limit)));
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    hits.add(new SearchHit(rows.getString("chunk_id"),
                            rows.getString("document_id"), rows.getString("content"),
                            rows.getDouble("score")));
                }
            }
            return hits;
        } catch (Exception e) {
            throw new RuntimeException("Failed to search pgvector knowledge index", e);
        }
    }

    private IndexVersion findVersion(String tenantId, String baseId, String versionId) {
        try (var connection = dataSource.getConnection()) {
            return version(connection, tenantId, baseId, versionId, false);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load pgvector index version", e);
        }
    }

    private static IndexVersion activeVersion(Connection connection, String tenantId,
                                              String baseId, boolean lock) throws Exception {
        String sql = """
                SELECT id, knowledge_base_id, based_on_version_id, status,
                       created_at, activated_at
                  FROM knowledge_index_versions
                 WHERE tenant_id=? AND knowledge_base_id=? AND status='ACTIVE'
                """ + (lock ? " FOR UPDATE" : "");
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, baseId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? mapVersion(rows) : null;
            }
        }
    }

    private static void lockBase(Connection connection, String tenantId, String baseId)
            throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT id FROM knowledge_bases
                 WHERE id=? AND tenant_id=?
                 FOR UPDATE
                """)) {
            statement.setString(1, baseId);
            statement.setString(2, tenantId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("Knowledge base not found");
            }
        }
    }

    private static IndexVersion version(Connection connection, String tenantId, String baseId,
                                        String versionId, boolean lock) throws Exception {
        String sql = """
                SELECT id, knowledge_base_id, based_on_version_id, status,
                       created_at, activated_at
                  FROM knowledge_index_versions
                 WHERE id=? AND tenant_id=? AND knowledge_base_id=?
                """ + (lock ? " FOR UPDATE" : "");
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, versionId);
            statement.setString(2, tenantId);
            statement.setString(3, baseId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? mapVersion(rows) : null;
            }
        }
    }

    private static IndexVersion mapVersion(ResultSet rows) throws Exception {
        var activated = rows.getTimestamp("activated_at");
        return new IndexVersion(rows.getString("id"), rows.getString("knowledge_base_id"),
                rows.getString("based_on_version_id"), rows.getString("status"),
                rows.getTimestamp("created_at").toInstant(),
                activated == null ? null : activated.toInstant());
    }

    private void requireDimension(float[] embedding) {
        if (embedding.length != dimension) {
            throw new IllegalArgumentException(
                    "Embedding dimension " + embedding.length + " != " + dimension);
        }
    }

    private static String vector(float[] values) {
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) result.append(',');
            result.append(values[i]);
        }
        return result.append(']').toString();
    }
}
