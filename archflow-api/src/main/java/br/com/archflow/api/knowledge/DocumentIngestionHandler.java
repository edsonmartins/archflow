package br.com.archflow.api.knowledge;

import br.com.archflow.api.jobs.JobContext;
import br.com.archflow.api.jobs.JobHandler;
import br.com.archflow.api.storage.FileStorageService;
import br.com.archflow.api.storage.StoredFile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

public class DocumentIngestionHandler implements JobHandler {
    private final KnowledgeRepository repository;
    private final FileStorageService files;
    private final RecursiveTextChunker chunker;
    private final int chunkSize;
    private final int overlap;
    private final KnowledgeEmbeddingModel embeddings;
    private final KnowledgeVectorIndex vectorIndex;
    private final DocumentTextExtractorRegistry extractors;

    public DocumentIngestionHandler(KnowledgeRepository repository,
                                    FileStorageService files,
                                    RecursiveTextChunker chunker,
                                    int chunkSize, int overlap,
                                    KnowledgeEmbeddingModel embeddings,
                                    KnowledgeVectorIndex vectorIndex,
                                    DocumentTextExtractorRegistry extractors) {
        this.repository = repository;
        this.files = files;
        this.chunker = chunker;
        this.chunkSize = chunkSize;
        this.overlap = overlap;
        this.embeddings = embeddings;
        this.vectorIndex = vectorIndex;
        this.extractors = extractors;
    }

    public String type() {
        return "DOCUMENT_INGESTION";
    }

    public void handle(JobContext context, Map<String, Object> payload) {
        String tenantId = required(payload, "tenantId");
        String documentId = required(payload, "documentId");
        String fileId = required(payload, "fileId");
        KnowledgeDocument document = repository.findDocument(tenantId, documentId);
        StoredFile file = files.metadata(tenantId, fileId);
        if (document == null || file == null) throw new IllegalArgumentException("Document or file not found");
        repository.saveDocument(withStatus(document, "PROCESSING", 0, null));
        try {
            context.progress(10, "Extracting text");
            String text = extractors.extract(file, files.content(tenantId, fileId));
            context.checkCancelled();
            context.progress(35, "Splitting document");
            var slices = chunker.split(text, chunkSize, overlap);
            var chunks = new ArrayList<DocumentChunk>(slices.size());
            for (int index = 0; index < slices.size(); index++) {
                var slice = slices.get(index);
                chunks.add(new DocumentChunk("chunk-" + UUID.randomUUID(), tenantId,
                        documentId, index, slice.content(),
                        slice.startOffset(), slice.endOffset()));
            }
            repository.replaceChunks(tenantId, documentId, chunks);
            context.checkCancelled();
            context.progress(65, "Generating embeddings");
            var vectors = embeddings.embedAll(
                    chunks.stream().map(DocumentChunk::content).toList());
            var embedded = java.util.stream.IntStream.range(0, chunks.size())
                    .mapToObj(index -> new KnowledgeVectorIndex.EmbeddedChunk(
                            chunks.get(index), vectors.get(index)))
                    .toList();
            context.progress(85, "Indexing vectors");
            var candidate = vectorIndex.createCandidate(
                    tenantId, document.knowledgeBaseId());
            vectorIndex.replaceDocument(tenantId, document.knowledgeBaseId(),
                    candidate.id(), documentId, embedded);
            if (!vectorIndex.activate(tenantId, document.knowledgeBaseId(), candidate.id())) {
                throw new IllegalStateException(
                        "Knowledge index changed during ingestion; retry required");
            }
            repository.saveDocument(withStatus(document, "READY", chunks.size(), null));
            context.progress(100, "Document ready");
        } catch (RuntimeException failure) {
            repository.saveDocument(withStatus(document, "FAILED", 0,
                    failure.getMessage() == null ? failure.toString() : failure.getMessage()));
            throw failure;
        }
    }

    private static KnowledgeDocument withStatus(
            KnowledgeDocument document, String status, int chunks, String error) {
        return new KnowledgeDocument(document.id(), document.tenantId(),
                document.knowledgeBaseId(), document.fileId(), status, chunks,
                error, document.createdAt(), Instant.now());
    }

    private static String required(Map<String, Object> payload, String name) {
        Object value = payload.get(name);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Missing job payload: " + name);
        }
        return value.toString();
    }
}
