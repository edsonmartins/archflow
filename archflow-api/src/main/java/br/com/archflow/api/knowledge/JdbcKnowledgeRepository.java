package br.com.archflow.api.knowledge;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

public class JdbcKnowledgeRepository implements KnowledgeRepository {
    private final DataSource dataSource;

    public JdbcKnowledgeRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public KnowledgeBase saveBase(KnowledgeBase base) {
        execute("""
                INSERT INTO knowledge_bases (id, tenant_id, workspace_id, name, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, base.id(), base.tenantId(), base.workspaceId(), base.name(),
                Timestamp.from(base.createdAt()));
        return base;
    }

    public KnowledgeBase findBase(String tenantId, String id) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT * FROM knowledge_bases WHERE tenant_id=? AND id=?")) {
            statement.setString(1, tenantId);
            statement.setString(2, id);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? mapBase(rows) : null;
            }
        } catch (Exception e) {
            throw failure("load knowledge base", e);
        }
    }

    public List<KnowledgeBase> listBases(String tenantId) {
        List<KnowledgeBase> result = new ArrayList<>();
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT * FROM knowledge_bases
                      WHERE tenant_id=? ORDER BY created_at DESC
                     """)) {
            statement.setString(1, tenantId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) result.add(mapBase(rows));
            }
            return result;
        } catch (Exception e) {
            throw failure("list knowledge bases", e);
        }
    }

    public KnowledgeDocument saveDocument(KnowledgeDocument document) {
        int updated = execute("""
                UPDATE knowledge_documents SET status=?, chunk_count=?, error=?, updated_at=?
                 WHERE tenant_id=? AND id=?
                """, document.status(), document.chunkCount(), document.error(),
                Timestamp.from(document.updatedAt()), document.tenantId(), document.id());
        if (updated == 0) {
            execute("""
                    INSERT INTO knowledge_documents
                        (id, tenant_id, knowledge_base_id, file_id, status, chunk_count,
                         error, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, document.id(), document.tenantId(), document.knowledgeBaseId(),
                    document.fileId(), document.status(), document.chunkCount(), document.error(),
                    Timestamp.from(document.createdAt()), Timestamp.from(document.updatedAt()));
        }
        return document;
    }

    public KnowledgeDocument findDocument(String tenantId, String id) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT * FROM knowledge_documents WHERE tenant_id=? AND id=?")) {
            statement.setString(1, tenantId);
            statement.setString(2, id);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? mapDocument(rows) : null;
            }
        } catch (Exception e) {
            throw failure("load knowledge document", e);
        }
    }

    public List<KnowledgeDocument> listDocuments(String tenantId, String baseId) {
        List<KnowledgeDocument> result = new ArrayList<>();
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT * FROM knowledge_documents
                      WHERE tenant_id=? AND knowledge_base_id=? ORDER BY created_at DESC
                     """)) {
            statement.setString(1, tenantId);
            statement.setString(2, baseId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) result.add(mapDocument(rows));
            }
            return result;
        } catch (Exception e) {
            throw failure("list knowledge documents", e);
        }
    }

    public void replaceChunks(String tenantId, String documentId, List<DocumentChunk> chunks) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var delete = connection.prepareStatement(
                        "DELETE FROM document_chunks WHERE tenant_id=? AND document_id=?")) {
                    delete.setString(1, tenantId);
                    delete.setString(2, documentId);
                    delete.executeUpdate();
                }
                try (var insert = connection.prepareStatement("""
                        INSERT INTO document_chunks
                            (id, tenant_id, document_id, position, content, start_offset, end_offset)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    for (DocumentChunk chunk : chunks) {
                        insert.setString(1, chunk.id());
                        insert.setString(2, tenantId);
                        insert.setString(3, documentId);
                        insert.setInt(4, chunk.position());
                        insert.setString(5, chunk.content());
                        insert.setInt(6, chunk.startOffset());
                        insert.setInt(7, chunk.endOffset());
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        } catch (Exception e) {
            throw failure("replace document chunks", e);
        }
    }

    public List<DocumentChunk> listChunks(String tenantId, String documentId) {
        List<DocumentChunk> result = new ArrayList<>();
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT * FROM document_chunks
                      WHERE tenant_id=? AND document_id=? ORDER BY position
                     """)) {
            statement.setString(1, tenantId);
            statement.setString(2, documentId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) result.add(new DocumentChunk(
                        rows.getString("id"), tenantId, documentId, rows.getInt("position"),
                        rows.getString("content"), rows.getInt("start_offset"),
                        rows.getInt("end_offset")));
            }
            return result;
        } catch (Exception e) {
            throw failure("list document chunks", e);
        }
    }

    private int execute(String sql, Object... values) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement.executeUpdate();
        } catch (Exception e) {
            throw failure("persist knowledge data", e);
        }
    }

    private static KnowledgeBase mapBase(ResultSet row) throws Exception {
        return new KnowledgeBase(row.getString("id"), row.getString("tenant_id"),
                row.getString("workspace_id"), row.getString("name"),
                row.getTimestamp("created_at").toInstant());
    }

    private static KnowledgeDocument mapDocument(ResultSet row) throws Exception {
        return new KnowledgeDocument(row.getString("id"), row.getString("tenant_id"),
                row.getString("knowledge_base_id"), row.getString("file_id"),
                row.getString("status"), row.getInt("chunk_count"), row.getString("error"),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant());
    }

    private static RuntimeException failure(String operation, Exception cause) {
        return new RuntimeException("Failed to " + operation, cause);
    }
}
