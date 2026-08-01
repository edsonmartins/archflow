package br.com.archflow.api.storage;

import java.util.List;

/** Metadata persistence. Every lookup requires the tenant boundary. */
public interface FileMetadataRepository {

    StoredFile save(StoredFile file);

    StoredFile find(String tenantId, String id);

    List<StoredFile> list(String tenantId, String workspaceId);

    boolean delete(String tenantId, String id);
}
