package br.com.archflow.api.knowledge;

import br.com.archflow.api.jobs.JobRecord;
import br.com.archflow.api.jobs.JobService;
import br.com.archflow.api.jobs.JobStatus;
import br.com.archflow.api.storage.FileStorageService;
import br.com.archflow.api.storage.StoredFile;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class KnowledgeService {
    private static final String INGESTION_JOB = "DOCUMENT_INGESTION";
    private static final int MAX_ATTEMPTS = 3;

    /**
     * Status mostrado para um documento que terminou de indexar (READY) mas não tem trechos na versão
     * <b>ativa</b> do índice — a busca não o alcança. Acontece depois de restaurar uma versão anterior
     * a ele. Não é gravado: o status gravado diz o desfecho da ingestão, e este, o que a versão ativa
     * contém. Antes a lista mostrava "Pronto", o contador o somava e o reenvio era recusado como
     * "já está nesta base", sem saída além de mudar os bytes do arquivo (RCHFLW-157).
     */
    public static final String NOT_IN_ACTIVE_INDEX = "NOT_IN_ACTIVE_INDEX";

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

    /**
     * Envia um arquivo para a base. A deduplicação é por conteúdo: há um único job por
     * {@code (base, sha256)}, e o documento é o que esse job processa.
     *
     * <p>Antes o documento era criado por arquivo e o job deduplicado por conteúdo, então o reenvio
     * do mesmo conteúdo criava um documento que o job antigo (já terminal) nunca processava: ficava
     * "Na fila" para sempre, e um documento que falhou não tinha como ser refeito (RCHFLW-111).
     * Agora o reenvio devolve o documento do job; se o job falhou ou foi cancelado, ele é reaberto.
     */
    public IngestionSubmission ingest(String tenantId, String baseId, String fileId) {
        KnowledgeBase base = repository.findBase(tenantId, baseId);
        StoredFile file = files.metadata(tenantId, fileId);
        if (base == null || file == null) return null;
        String key = "document-ingestion:" + baseId + ":" + file.sha256();
        JobRecord previous = jobs.findByIdempotencyKey(tenantId, INGESTION_JOB, key);
        if (previous == null) {
            String documentId = "doc-" + UUID.nameUUIDFromBytes(
                    (tenantId + "|" + baseId + "|" + fileId).getBytes(StandardCharsets.UTF_8));
            Instant now = Instant.now();
            KnowledgeDocument existing = repository.findDocument(tenantId, documentId);
            KnowledgeDocument document = existing != null ? existing : repository.saveDocument(
                    new KnowledgeDocument(documentId, tenantId, baseId, fileId,
                            "QUEUED", 0, null, now, now));
            JobRecord job = jobs.submit(tenantId, base.workspaceId(), INGESTION_JOB,
                    Map.of("tenantId", tenantId, "documentId", documentId, "fileId", fileId),
                    key, MAX_ATTEMPTS);
            if (documentId.equals(documentIdOf(job))) {
                return new IngestionSubmission(document, job, IngestionOutcome.QUEUED);
            }
            // Outro envio do mesmo conteúdo criou o job entre a consulta e o submit. O documento
            // criado acima ficou sem job; é tratado como os órfãos, logo abaixo.
            previous = job;
        }
        return reuse(tenantId, baseId, file, previous);
    }

    private IngestionSubmission reuse(String tenantId, String baseId, StoredFile file, JobRecord job) {
        String documentId = documentIdOf(job);
        KnowledgeDocument document = repository.findDocument(tenantId, documentId);
        if (document == null) {
            // Nenhum caminho apaga o documento de um job; se acontecer, é dado corrompido, e criar
            // outro documento aqui esconderia isso.
            throw new IllegalStateException("Document of ingestion job " + job.id() + " not found");
        }
        IngestionOutcome outcome = IngestionOutcome.ALREADY_PRESENT;
        if (job.status() == JobStatus.SUCCEEDED && outOfActiveIndex(document,
                vectorIndex.indexedDocumentIds(tenantId, baseId))) {
            // O conteúdo foi indexado, mas a versão ativa não o tem mais (restaurada a um snapshot
            // anterior a ele): o reenvio o reindexa. A nova ingestão parte da versão ativa e a ativa
            // com ele, sem desfazer o rollback para os outros documentos. O documento não volta para
            // QUEUED aqui: um worker rápido pode já tê-lo levado a READY, e o QUEUED gravado depois
            // ficaria para sempre ao lado de um job concluído — o status mostrado já diz que ele está
            // fora do índice até o worker terminar.
            JobRecord reopened = jobs.reopen(tenantId, job.id(), MAX_ATTEMPTS,
                    Map.of("tenantId", tenantId, "documentId", documentId, "fileId", file.id()),
                    Set.of(JobStatus.SUCCEEDED));
            if (reopened != null) {
                job = reopened;
                outcome = IngestionOutcome.REPROCESSING;
            } else {
                job = jobs.get(tenantId, job.id());
            }
        } else if (job.status() == JobStatus.FAILED || job.status() == JobStatus.CANCELLED) {
            // O estado do job foi lido antes e pode estar velho: outro reenvio pode já ter reaberto
            // o job e um worker ter levado o documento a READY. Por isso só quem de fato reabriu
            // o job (o UPDATE condicional devolve null aos demais) mexe no documento; antes, o
            // perdedor gravava QUEUED por cima do READY, ao lado de um job SUCCEEDED, e nenhum
            // reenvio consertava. O documento vem depois do job, então um worker rápido pode já
            // tê-lo gravado: requeueDocument não sai de READY, e sobre PROCESSING o QUEUED é só
            // transitório, porque o último write do worker é incondicional.
            // O reprocessamento usa o arquivo deste envio, não o do primeiro: o blob original pode
            // ter sumido (DELETE /api/files apaga o blob antes de a FK barrar o registro), e o
            // conteúdo é o mesmo, porque a chave do job é o sha256.
            JobRecord reopened = jobs.reopen(tenantId, job.id(), MAX_ATTEMPTS,
                    Map.of("tenantId", tenantId, "documentId", documentId, "fileId", file.id()));
            if (reopened != null) {
                repository.requeueDocument(tenantId, documentId, file.id());
                job = reopened;
                outcome = IngestionOutcome.REPROCESSING;
            } else {
                // Outro envio reabriu o job antes deste: o reprocessamento já está em curso (ou
                // terminou), e a resposta mostra o estado real em vez de anunciar outro.
                job = jobs.get(tenantId, job.id());
            }
            document = repository.findDocument(tenantId, documentId);
        }
        discardOrphans(tenantId, baseId, file.sha256(), documentId);
        return new IngestionSubmission(
                shown(document, vectorIndex.indexedDocumentIds(tenantId, baseId)), job, outcome);
    }

    /** Pronto e com trechos, mas sem nenhum deles na versão ativa do índice. */
    private static boolean outOfActiveIndex(KnowledgeDocument document, Set<String> indexed) {
        return "READY".equals(document.status()) && document.chunkCount() > 0
                && !indexed.contains(document.id());
    }

    /** O documento como a tela o vê: o status diz se a busca o alcança ({@link #NOT_IN_ACTIVE_INDEX}). */
    private static KnowledgeDocument shown(KnowledgeDocument document, Set<String> indexed) {
        if (!outOfActiveIndex(document, indexed)) return document;
        return new KnowledgeDocument(document.id(), document.tenantId(), document.knowledgeBaseId(),
                document.fileId(), NOT_IN_ACTIVE_INDEX, document.chunkCount(), document.error(),
                document.createdAt(), document.updatedAt());
    }

    /**
     * Apaga os documentos QUEUED da base com o mesmo conteúdo que não são o documento do job. Como
     * o job é único por conteúdo, nenhum deles tem job: são os órfãos que a versão anterior criava a
     * cada reenvio (e o que perdeu a corrida em {@link #ingest}). Não têm trechos nem vetores.
     */
    private void discardOrphans(String tenantId, String baseId, String sha256, String keep) {
        for (KnowledgeDocument candidate : repository.listDocuments(tenantId, baseId)) {
            if (candidate.id().equals(keep) || !"QUEUED".equals(candidate.status())) continue;
            StoredFile candidateFile = files.metadata(tenantId, candidate.fileId());
            if (candidateFile != null && sha256.equals(candidateFile.sha256())) {
                repository.deleteQueuedDocument(tenantId, candidate.id());
            }
        }
    }

    private static String documentIdOf(JobRecord job) {
        Object documentId = job.payload().get("documentId");
        return documentId == null ? null : documentId.toString();
    }

    public List<KnowledgeDocument> documents(String tenantId, String baseId) {
        if (repository.findBase(tenantId, baseId) == null) return null;
        Set<String> indexed = vectorIndex.indexedDocumentIds(tenantId, baseId);
        return repository.listDocuments(tenantId, baseId).stream()
                .map(document -> shown(document, indexed))
                .toList();
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

    /**
     * {@code outcome} diz o que o envio fez, para a tela avisar quando o conteúdo já estava na base
     * em vez de anunciar uma indexação que não vai acontecer.
     */
    public record IngestionSubmission(
            KnowledgeDocument document, JobRecord job, IngestionOutcome outcome) {}

    public enum IngestionOutcome {
        /** Conteúdo novo nesta base: documento e job criados agora. */
        QUEUED,
        /** O conteúdo já estava na base (job na fila, rodando ou concluído): nada foi criado. */
        ALREADY_PRESENT,
        /** O conteúdo já estava na base, mas o job falhou ou foi cancelado: foi reaberto. */
        REPROCESSING
    }
}
