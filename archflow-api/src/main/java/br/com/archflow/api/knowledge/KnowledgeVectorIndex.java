package br.com.archflow.api.knowledge;

import java.util.List;

public interface KnowledgeVectorIndex {
    IndexVersion createCandidate(String tenantId, String baseId);
    void replaceDocument(String tenantId, String baseId, String versionId, String documentId,
                         List<EmbeddedChunk> chunks);
    boolean activate(String tenantId, String baseId, String versionId);
    boolean rollback(String tenantId, String baseId, String versionId);
    IndexVersion activeVersion(String tenantId, String baseId);
    List<IndexVersion> versions(String tenantId, String baseId);
    List<SearchHit> search(String tenantId, String baseId, String versionId, float[] query,
                           int limit, double minScore);

    record EmbeddedChunk(DocumentChunk chunk, float[] embedding) {}
    record SearchHit(String chunkId, String documentId, String content, double score) {}
    record IndexVersion(String id, String baseId, String basedOnVersionId,
                        String status, java.time.Instant createdAt,
                        java.time.Instant activatedAt) {}
}
