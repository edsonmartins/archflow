package br.com.archflow.api.knowledge;

import java.util.List;

public interface KnowledgeRepository {
    KnowledgeBase saveBase(KnowledgeBase base);
    KnowledgeBase findBase(String tenantId, String id);
    List<KnowledgeBase> listBases(String tenantId);
    KnowledgeDocument saveDocument(KnowledgeDocument document);
    KnowledgeDocument findDocument(String tenantId, String id);
    List<KnowledgeDocument> listDocuments(String tenantId, String baseId);
    void replaceChunks(String tenantId, String documentId, List<DocumentChunk> chunks);
    List<DocumentChunk> listChunks(String tenantId, String documentId);
}
