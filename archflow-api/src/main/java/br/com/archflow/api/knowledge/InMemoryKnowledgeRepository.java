package br.com.archflow.api.knowledge;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryKnowledgeRepository implements KnowledgeRepository {
    private final Map<String, KnowledgeBase> bases = new ConcurrentHashMap<>();
    private final Map<String, KnowledgeDocument> documents = new ConcurrentHashMap<>();
    private final Map<String, List<DocumentChunk>> chunks = new ConcurrentHashMap<>();

    public KnowledgeBase saveBase(KnowledgeBase base) {
        bases.put(base.id(), base);
        return base;
    }

    public KnowledgeBase findBase(String tenantId, String id) {
        KnowledgeBase base = bases.get(id);
        return base != null && base.tenantId().equals(tenantId) ? base : null;
    }

    public List<KnowledgeBase> listBases(String tenantId) {
        return bases.values().stream().filter(base -> base.tenantId().equals(tenantId))
                .sorted(Comparator.comparing(KnowledgeBase::createdAt).reversed()).toList();
    }

    public KnowledgeDocument saveDocument(KnowledgeDocument document) {
        documents.put(document.id(), document);
        return document;
    }

    public KnowledgeDocument findDocument(String tenantId, String id) {
        KnowledgeDocument document = documents.get(id);
        return document != null && document.tenantId().equals(tenantId) ? document : null;
    }

    public List<KnowledgeDocument> listDocuments(String tenantId, String baseId) {
        return documents.values().stream()
                .filter(document -> document.tenantId().equals(tenantId))
                .filter(document -> document.knowledgeBaseId().equals(baseId))
                .sorted(Comparator.comparing(KnowledgeDocument::createdAt).reversed()).toList();
    }

    public void replaceChunks(String tenantId, String documentId, List<DocumentChunk> replacement) {
        KnowledgeDocument document = findDocument(tenantId, documentId);
        if (document == null) throw new IllegalArgumentException("Document not found");
        chunks.put(documentId, List.copyOf(replacement));
    }

    public List<DocumentChunk> listChunks(String tenantId, String documentId) {
        return findDocument(tenantId, documentId) == null
                ? List.of() : chunks.getOrDefault(documentId, List.of());
    }
}
