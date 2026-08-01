package br.com.archflow.api.knowledge;

import br.com.archflow.api.jobs.JobRecord;
import br.com.archflow.api.jobs.JobService;
import br.com.archflow.api.storage.FileStorageService;
import br.com.archflow.api.storage.StoredFile;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class KnowledgeService {
    private final KnowledgeRepository repository;
    private final FileStorageService files;
    private final JobService jobs;
    private final KnowledgeEmbeddingModel embeddings;
    private final KnowledgeVectorIndex vectorIndex;

    public KnowledgeService(KnowledgeRepository repository,
                            FileStorageService files, JobService jobs,
                            KnowledgeEmbeddingModel embeddings,
                            KnowledgeVectorIndex vectorIndex) {
        this.repository = repository;
        this.files = files;
        this.jobs = jobs;
        this.embeddings = embeddings;
        this.vectorIndex = vectorIndex;
    }

    public KnowledgeBase createBase(String tenantId, String workspaceId, String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name cannot be blank");
        return repository.saveBase(new KnowledgeBase(
                "kb-" + UUID.randomUUID(), tenantId, workspaceId, name.trim(), Instant.now()));
    }

    public List<KnowledgeBase> bases(String tenantId) {
        return repository.listBases(tenantId);
    }

    public IngestionSubmission ingest(String tenantId, String baseId, String fileId) {
        KnowledgeBase base = repository.findBase(tenantId, baseId);
        StoredFile file = files.metadata(tenantId, fileId);
        if (base == null || file == null) return null;
        String documentId = "doc-" + UUID.nameUUIDFromBytes(
                (tenantId + "|" + baseId + "|" + fileId).getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        KnowledgeDocument existing = repository.findDocument(tenantId, documentId);
        KnowledgeDocument document = existing != null ? existing : repository.saveDocument(
                new KnowledgeDocument(documentId, tenantId, baseId, fileId,
                        "QUEUED", 0, null, now, now));
        String key = "document-ingestion:" + baseId + ":" + file.sha256();
        JobRecord job = jobs.submit(tenantId, base.workspaceId(), "DOCUMENT_INGESTION",
                Map.of("tenantId", tenantId, "documentId", documentId, "fileId", fileId),
                key, 3);
        return new IngestionSubmission(document, job);
    }

    public List<KnowledgeDocument> documents(String tenantId, String baseId) {
        return repository.findBase(tenantId, baseId) == null
                ? null : repository.listDocuments(tenantId, baseId);
    }

    public List<DocumentChunk> chunks(String tenantId, String documentId) {
        return repository.findDocument(tenantId, documentId) == null
                ? null : repository.listChunks(tenantId, documentId);
    }

    public List<KnowledgeVectorIndex.SearchHit> search(
            String tenantId, String baseId, String versionId,
            String query, int limit, double minScore) {
        if (repository.findBase(tenantId, baseId) == null) return null;
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query cannot be blank");
        }
        return vectorIndex.search(tenantId, baseId, versionId, embeddings.embed(query),
                Math.max(1, Math.min(50, limit)), minScore);
    }

    public List<KnowledgeVectorIndex.SearchHit> search(
            String tenantId, String baseId, String query, int limit, double minScore) {
        return search(tenantId, baseId, null, query, limit, minScore);
    }

    public List<KnowledgeVectorIndex.IndexVersion> indexVersions(
            String tenantId, String baseId) {
        return repository.findBase(tenantId, baseId) == null
                ? null : vectorIndex.versions(tenantId, baseId);
    }

    public KnowledgeVectorIndex.IndexVersion rollbackIndex(
            String tenantId, String baseId, String versionId) {
        if (repository.findBase(tenantId, baseId) == null) return null;
        return vectorIndex.rollback(tenantId, baseId, versionId)
                ? vectorIndex.activeVersion(tenantId, baseId) : null;
    }

    public record IngestionSubmission(KnowledgeDocument document, JobRecord job) {}
}
