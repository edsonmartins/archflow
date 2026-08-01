package br.com.archflow.api.storage;

import java.time.Instant;

/** Tenant-scoped metadata for a blob kept by {@link ObjectStorage}. */
public record StoredFile(
        String id,
        String tenantId,
        String workspaceId,
        String originalName,
        String contentType,
        long size,
        String sha256,
        String storageKey,
        Instant createdAt) {
}
