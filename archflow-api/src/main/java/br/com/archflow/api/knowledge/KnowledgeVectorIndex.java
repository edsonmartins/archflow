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

    /**
     * Os documentos que têm trechos na versão <b>ativa</b> do índice da base — o que a busca de fato
     * alcança. Vazio sem versão ativa.
     *
     * <p>O status do documento é global e a versão ativa pode voltar a um snapshot anterior a ele
     * (rollback): é por aqui que o serviço sabe que um documento "pronto" não está mais na busca
     * (RCHFLW-157).</p>
     */
    java.util.Set<String> indexedDocumentIds(String tenantId, String baseId);

    record EmbeddedChunk(DocumentChunk chunk, float[] embedding) {}
    record SearchHit(String chunkId, String documentId, String content, double score) {}
    record IndexVersion(String id, String baseId, String basedOnVersionId,
                        String status, java.time.Instant createdAt,
                        java.time.Instant activatedAt) {}
}
