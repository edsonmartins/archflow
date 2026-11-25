package br.com.archflow.api.knowledge;

import java.util.List;

public interface KnowledgeRepository {
    KnowledgeBase saveBase(KnowledgeBase base);
    KnowledgeBase findBase(String tenantId, String id);
    List<KnowledgeBase> listBases(String tenantId);
    KnowledgeDocument saveDocument(KnowledgeDocument document);
    KnowledgeDocument findDocument(String tenantId, String id);
    List<KnowledgeDocument> listDocuments(String tenantId, String baseId);
    /**
     * Apaga um documento ainda QUEUED; um documento em outro estado fica onde está. Serve para os
     * duplicados que nunca tiveram job próprio (RCHFLW-111): não têm trechos nem vetores.
     */
    void deleteQueuedDocument(String tenantId, String id);
    /**
     * Devolve à fila um documento que não está READY, apontando-o para {@code fileId}, e diz se
     * mudou algo. Roda logo depois de o job ser reaberto, então um worker pode já ter pegado o job:
     * a condição no estado protege o READY que ele gravou; QUEUED e PROCESSING entram porque o
     * último write do worker (READY ou FAILED) é incondicional e decide o estado final.
     */
    boolean requeueDocument(String tenantId, String id, String fileId);
    void replaceChunks(String tenantId, String documentId, List<DocumentChunk> chunks);
    List<DocumentChunk> listChunks(String tenantId, String documentId);
}
