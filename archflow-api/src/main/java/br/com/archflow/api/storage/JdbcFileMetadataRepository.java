package br.com.archflow.api.storage;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/** JDBC metadata repository over {@code stored_files}. */
public class JdbcFileMetadataRepository implements FileMetadataRepository {

    private final DataSource dataSource;

    public JdbcFileMetadataRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public StoredFile save(StoredFile file) {
        String sql = """
                INSERT INTO stored_files
                    (id, tenant_id, workspace_id, original_name, content_type,
                     size_bytes, sha256, storage_key, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, file.id());
            statement.setString(2, file.tenantId());
            statement.setString(3, file.workspaceId());
            statement.setString(4, file.originalName());
            statement.setString(5, file.contentType());
            statement.setLong(6, file.size());
            statement.setString(7, file.sha256());
            statement.setString(8, file.storageKey());
            statement.setTimestamp(9, Timestamp.from(file.createdAt()));
            statement.executeUpdate();
            return file;
        } catch (SQLException e) {
            throw new StorageException("Failed to save file metadata " + file.id(), e);
        }
    }

    @Override
    public StoredFile find(String tenantId, String id) {
        String sql = "SELECT * FROM stored_files WHERE tenant_id = ? AND id = ?";
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, id);
            try (var result = statement.executeQuery()) {
                return result.next() ? map(result) : null;
            }
        } catch (SQLException e) {
            throw new StorageException("Failed to load file metadata " + id, e);
        }
    }

    @Override
    public List<StoredFile> list(String tenantId, String workspaceId) {
        String sql = workspaceId == null
                ? "SELECT * FROM stored_files WHERE tenant_id = ? ORDER BY created_at DESC"
                : """
                  SELECT * FROM stored_files
                   WHERE tenant_id = ? AND workspace_id = ?
                   ORDER BY created_at DESC
                  """;
        List<StoredFile> files = new ArrayList<>();
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            if (workspaceId != null) statement.setString(2, workspaceId);
            try (var result = statement.executeQuery()) {
                while (result.next()) files.add(map(result));
            }
            return files;
        } catch (SQLException e) {
            throw new StorageException("Failed to list file metadata", e);
        }
    }

    @Override
    public boolean delete(String tenantId, String id) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "DELETE FROM stored_files WHERE tenant_id = ? AND id = ?")) {
            statement.setString(1, tenantId);
            statement.setString(2, id);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new StorageException("Failed to delete file metadata " + id, e);
        }
    }

    private static StoredFile map(ResultSet result) throws SQLException {
        return new StoredFile(
                result.getString("id"),
                result.getString("tenant_id"),
                result.getString("workspace_id"),
                result.getString("original_name"),
                result.getString("content_type"),
                result.getLong("size_bytes"),
                result.getString("sha256"),
                result.getString("storage_key"),
                result.getTimestamp("created_at").toInstant());
    }
}
