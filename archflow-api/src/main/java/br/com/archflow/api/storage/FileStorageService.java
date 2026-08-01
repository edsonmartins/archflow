package br.com.archflow.api.storage;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Coordinates blob writes with tenant-scoped metadata. */
public class FileStorageService {

    private final ObjectStorage storage;
    private final FileMetadataRepository repository;
    private final long maxFileSize;

    public FileStorageService(ObjectStorage storage, FileMetadataRepository repository,
                              long maxFileSize) {
        this.storage = storage;
        this.repository = repository;
        this.maxFileSize = maxFileSize;
    }

    public StoredFile store(String tenantId, String workspaceId, String originalName,
                            String contentType, byte[] content) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("File cannot be empty");
        }
        if (content.length > maxFileSize) {
            throw new IllegalArgumentException(
                    "File exceeds maximum size of " + maxFileSize + " bytes");
        }
        String id = "file-" + UUID.randomUUID();
        String storageKey = id.substring(5, 7) + "/" + id;
        var metadata = new StoredFile(
                id,
                require(tenantId, "tenantId"),
                blankToNull(workspaceId),
                safeName(originalName),
                contentType == null || contentType.isBlank()
                        ? "application/octet-stream" : contentType,
                content.length,
                sha256(content),
                storageKey,
                Instant.now());
        storage.put(storageKey, content);
        try {
            return repository.save(metadata);
        } catch (RuntimeException failure) {
            storage.delete(storageKey);
            throw failure;
        }
    }

    public List<StoredFile> list(String tenantId, String workspaceId) {
        return repository.list(require(tenantId, "tenantId"), blankToNull(workspaceId));
    }

    public StoredFile metadata(String tenantId, String id) {
        return repository.find(require(tenantId, "tenantId"), id);
    }

    public byte[] content(String tenantId, String id) {
        StoredFile file = metadata(tenantId, id);
        return file == null ? null : storage.get(file.storageKey());
    }

    public boolean delete(String tenantId, String id) {
        StoredFile file = metadata(tenantId, id);
        if (file == null) return false;
        storage.delete(file.storageKey());
        return repository.delete(tenantId, id);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String safeName(String name) {
        if (name == null || name.isBlank()) return "unnamed";
        String normalized = name.replace('\\', '/');
        String lastSegment = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        return lastSegment.isBlank() ? "unnamed" : lastSegment;
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
