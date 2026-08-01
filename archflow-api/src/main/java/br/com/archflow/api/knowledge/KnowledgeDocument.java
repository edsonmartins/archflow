package br.com.archflow.api.knowledge;

import java.time.Instant;

public record KnowledgeDocument(
        String id,
        String tenantId,
        String knowledgeBaseId,
        String fileId,
        String status,
        int chunkCount,
        String error,
        Instant createdAt,
        Instant updatedAt) {
}
