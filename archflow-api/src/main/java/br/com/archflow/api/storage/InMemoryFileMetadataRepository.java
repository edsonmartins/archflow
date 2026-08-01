package br.com.archflow.api.storage;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Development metadata repository with the same tenant semantics as JDBC. */
public class InMemoryFileMetadataRepository implements FileMetadataRepository {

    private final Map<String, StoredFile> files = new ConcurrentHashMap<>();

    @Override
    public StoredFile save(StoredFile file) {
        files.put(file.id(), file);
        return file;
    }

    @Override
    public StoredFile find(String tenantId, String id) {
        StoredFile file = files.get(id);
        return file != null && file.tenantId().equals(tenantId) ? file : null;
    }

    @Override
    public List<StoredFile> list(String tenantId, String workspaceId) {
        return files.values().stream()
                .filter(file -> file.tenantId().equals(tenantId))
                .filter(file -> workspaceId == null || workspaceId.equals(file.workspaceId()))
                .sorted(Comparator.comparing(StoredFile::createdAt).reversed())
                .toList();
    }

    @Override
    public boolean delete(String tenantId, String id) {
        StoredFile file = find(tenantId, id);
        return file != null && files.remove(id, file);
    }
}
