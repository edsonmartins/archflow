package br.com.archflow.api.knowledge;

import br.com.archflow.api.jobs.*;
import br.com.archflow.api.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentIngestionTest {

    @TempDir
    Path directory;

    @Test
    void ingestsTextFileIntoDeterministicOrderedChunks() {
        Fixture fixture = fixture();
        StoredFile file = fixture.files.store("tenant", "workspace", "guide.md",
                "text/markdown", """
                        First paragraph explains the product and its purpose.

                        Second paragraph contains operational details and examples.

                        Third paragraph closes the document.
                        """.getBytes(StandardCharsets.UTF_8));
        KnowledgeBase base = fixture.knowledge.createBase("tenant", "workspace", "Docs");
        var submission = fixture.knowledge.ingest("tenant", base.id(), file.id());

        try (var worker = fixture.worker()) {
            assertThat(worker.runOnce()).isTrue();
        }

        KnowledgeDocument document = fixture.repository.findDocument(
                "tenant", submission.document().id());
        List<DocumentChunk> chunks = fixture.repository.listChunks("tenant", document.id());
        assertThat(document.status()).isEqualTo("READY");
        assertThat(document.chunkCount()).isEqualTo(chunks.size());
        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).extracting(DocumentChunk::position)
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.range(0, chunks.size()).boxed().toList());
        assertThat(fixture.knowledge.search(
                "tenant", base.id(), "operational examples", 3, 0.0))
                .isNotEmpty()
                .first()
                .extracting(KnowledgeVectorIndex.SearchHit::documentId)
                .isEqualTo(document.id());
    }

    @Test
    void repeatedSubmissionIsIdempotent() {
        Fixture fixture = fixture();
        StoredFile file = fixture.files.store(
                "tenant", null, "a.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));
        KnowledgeBase base = fixture.knowledge.createBase("tenant", null, "Docs");

        var first = fixture.knowledge.ingest("tenant", base.id(), file.id());
        var second = fixture.knowledge.ingest("tenant", base.id(), file.id());

        assertThat(second.document().id()).isEqualTo(first.document().id());
        assertThat(second.job().id()).isEqualTo(first.job().id());
        assertThat(fixture.repository.listDocuments("tenant", base.id())).hasSize(1);
    }

    @Test
    void unsupportedFileFailsJobAndDocument() {
        Fixture fixture = fixture();
        StoredFile file = fixture.files.store(
                "tenant", null, "image.png", "image/png", new byte[]{1, 2, 3});
        KnowledgeBase base = fixture.knowledge.createBase("tenant", null, "Docs");
        var submission = fixture.knowledge.ingest("tenant", base.id(), file.id());

        try (var worker = fixture.worker()) {
            worker.runOnce();
        }

        assertThat(fixture.jobs.get("tenant", submission.job().id()).status())
                .isEqualTo(JobStatus.QUEUED);
        assertThat(fixture.repository.findDocument("tenant", submission.document().id()).status())
                .isEqualTo("FAILED");
    }

    @Test
    void createsCompleteSnapshotsAndCanRollbackTheActiveIndex() {
        Fixture fixture = fixture();
        KnowledgeBase base = fixture.knowledge.createBase("tenant", "workspace", "Docs");
        StoredFile firstFile = fixture.files.store("tenant", "workspace", "first.txt",
                "text/plain", "alpha architecture reference".getBytes(StandardCharsets.UTF_8));
        StoredFile secondFile = fixture.files.store("tenant", "workspace", "second.txt",
                "text/plain", "beta deployment handbook".getBytes(StandardCharsets.UTF_8));

        var first = fixture.knowledge.ingest("tenant", base.id(), firstFile.id());
        try (var worker = fixture.worker()) {
            assertThat(worker.runOnce()).isTrue();
        }
        String firstVersion = fixture.vectorIndex.activeVersion("tenant", base.id()).id();

        var second = fixture.knowledge.ingest("tenant", base.id(), secondFile.id());
        try (var worker = fixture.worker()) {
            assertThat(worker.runOnce()).isTrue();
        }
        String secondVersion = fixture.vectorIndex.activeVersion("tenant", base.id()).id();

        assertThat(secondVersion).isNotEqualTo(firstVersion);
        assertThat(fixture.knowledge.indexVersions("tenant", base.id())).hasSize(2);
        assertThat(fixture.knowledge.search(
                "tenant", base.id(), null, "reference", 10, -1.0))
                .extracting(KnowledgeVectorIndex.SearchHit::documentId)
                .contains(first.document().id(), second.document().id());

        assertThat(fixture.knowledge.rollbackIndex("tenant", base.id(), firstVersion).id())
                .isEqualTo(firstVersion);
        assertThat(fixture.knowledge.search(
                "tenant", base.id(), null, "deployment", 10, -1.0))
                .extracting(KnowledgeVectorIndex.SearchHit::documentId)
                .contains(first.document().id())
                .doesNotContain(second.document().id());
    }

    @Test
    void rejectsStaleCandidateActivation() {
        var index = new InMemoryKnowledgeVectorIndex();
        var first = index.createCandidate("tenant", "base");
        var stale = index.createCandidate("tenant", "base");

        assertThat(index.activate("tenant", "base", first.id())).isTrue();
        assertThat(index.activate("tenant", "base", stale.id())).isFalse();
        assertThat(index.activeVersion("tenant", "base").id()).isEqualTo(first.id());
    }

    // RCHFLW-111: reenvio do mesmo conteúdo. Antes cada envio criava um documento novo, e o job
    // deduplicado por conteúdo apontava para o antigo: o novo ficava QUEUED para sempre.

    @Test
    void resubmittingSameContentAfterSuccessReturnsTheExistingDocument() {
        Fixture fixture = fixture();
        KnowledgeBase base = fixture.knowledge.createBase("tenant", null, "Docs");
        var first = fixture.knowledge.ingest("tenant", base.id(), store(fixture, "troca.txt"));
        try (var worker = fixture.worker()) {
            assertThat(worker.runOnce()).isTrue();
        }

        var second = fixture.knowledge.ingest("tenant", base.id(), store(fixture, "troca-2.txt"));

        assertThat(first.outcome()).isEqualTo(KnowledgeService.IngestionOutcome.QUEUED);
        assertThat(second.outcome()).isEqualTo(KnowledgeService.IngestionOutcome.ALREADY_PRESENT);
        assertThat(second.document().id()).isEqualTo(first.document().id());
        assertThat(second.document().status()).isEqualTo("READY");
        assertThat(second.job().id()).isEqualTo(first.job().id());
        assertThat(second.job().status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(fixture.repository.listDocuments("tenant", base.id()))
                .extracting(KnowledgeDocument::id).containsExactly(first.document().id());
        try (var worker = fixture.worker()) {
            assertThat(worker.runOnce()).as("nada novo na fila").isFalse();
        }
    }

    @Test
    void resubmittingSameContentWhileQueuedDoesNotCreateAnotherDocument() {
        Fixture fixture = fixture();
        KnowledgeBase base = fixture.knowledge.createBase("tenant", null, "Docs");
        var first = fixture.knowledge.ingest("tenant", base.id(), store(fixture, "troca.txt"));

        var second = fixture.knowledge.ingest("tenant", base.id(), store(fixture, "troca-2.txt"));
        try (var worker = fixture.worker()) {
            assertThat(worker.runOnce()).isTrue();
        }

        assertThat(second.outcome()).isEqualTo(KnowledgeService.IngestionOutcome.ALREADY_PRESENT);
        assertThat(second.document().id()).isEqualTo(first.document().id());
        assertThat(fixture.repository.listDocuments("tenant", base.id()))
                .singleElement().satisfies(document -> {
                    assertThat(document.id()).isEqualTo(first.document().id());
                    assertThat(document.status()).isEqualTo("READY");
                });
    }

    @Test
    void resubmittingSameContentAfterFailureReprocessesTheDocument() {
        AtomicBoolean providerDown = new AtomicBoolean(true);
        Fixture fixture = fixture(new FlakyEmbeddings(providerDown));
        KnowledgeBase base = fixture.knowledge.createBase("tenant", null, "Docs");
        var first = fixture.knowledge.ingest("tenant", base.id(), store(fixture, "troca.txt"));
        drain(fixture);
        assertThat(fixture.jobs.get("tenant", first.job().id()).status()).isEqualTo(JobStatus.FAILED);
        assertThat(fixture.repository.findDocument("tenant", first.document().id()).status())
                .isEqualTo("FAILED");

        providerDown.set(false);
        var second = fixture.knowledge.ingest("tenant", base.id(), store(fixture, "troca-2.txt"));

        assertThat(second.outcome()).isEqualTo(KnowledgeService.IngestionOutcome.REPROCESSING);
        assertThat(second.document().id()).isEqualTo(first.document().id());
        assertThat(second.document().status()).isEqualTo("QUEUED");
        assertThat(second.document().error()).isNull();
        assertThat(second.job().id()).isEqualTo(first.job().id());
        assertThat(second.job().status()).isEqualTo(JobStatus.QUEUED);
        drain(fixture);
        assertThat(fixture.repository.listDocuments("tenant", base.id()))
                .singleElement().satisfies(document -> {
                    assertThat(document.status()).isEqualTo("READY");
                    assertThat(document.error()).isNull();
                });
        assertThat(fixture.jobs.get("tenant", first.job().id()).status())
                .isEqualTo(JobStatus.SUCCEEDED);
        assertThat(fixture.jobs.attempts("tenant", first.job().id()))
                .as("o histórico das tentativas anteriores continua lá")
                .extracting(JobAttempt::outcome)
                .containsExactly(JobStatus.FAILED, JobStatus.FAILED, JobStatus.FAILED,
                        JobStatus.SUCCEEDED);
    }

    @Test
    void resubmittingSameContentAfterCancellationReprocessesTheDocument() {
        Fixture fixture = fixture();
        KnowledgeBase base = fixture.knowledge.createBase("tenant", null, "Docs");
        var first = fixture.knowledge.ingest("tenant", base.id(), store(fixture, "troca.txt"));
        fixture.jobs.cancel("tenant", first.job().id());

        var second = fixture.knowledge.ingest("tenant", base.id(), store(fixture, "troca-2.txt"));
        drain(fixture);

        assertThat(second.outcome()).isEqualTo(KnowledgeService.IngestionOutcome.REPROCESSING);
        assertThat(second.job().cancelRequested()).isFalse();
        assertThat(fixture.repository.findDocument("tenant", first.document().id()).status())
                .isEqualTo("READY");
    }

    @Test
    void resubmittingResolvesOrphansLeftByThePreviousBehaviour() {
        Fixture fixture = fixture();
        KnowledgeBase base = fixture.knowledge.createBase("tenant", null, "Docs");
        var first = fixture.knowledge.ingest("tenant", base.id(), store(fixture, "troca.txt"));
        drain(fixture);
        // O que a versão anterior deixava a cada reenvio: documento QUEUED sem job próprio.
        String orphanFile = store(fixture, "troca-orfao.txt");
        var now = java.time.Instant.now();
        fixture.repository.saveDocument(new KnowledgeDocument("doc-orfao", "tenant", base.id(),
                orphanFile, "QUEUED", 0, null, now, now));
        String otherContent = fixture.files.store("tenant", null, "outro.txt", "text/plain",
                "conteúdo diferente".getBytes(StandardCharsets.UTF_8)).id();
        var other = fixture.knowledge.ingest("tenant", base.id(), otherContent);

        fixture.knowledge.ingest("tenant", base.id(), store(fixture, "troca-3.txt"));

        assertThat(fixture.repository.listDocuments("tenant", base.id()))
                .extracting(KnowledgeDocument::id)
                .as("o órfão sai; o documento de outro conteúdo, ainda na fila, fica")
                .containsExactlyInAnyOrder(first.document().id(), other.document().id());
    }

    @Test
    void aResubmissionThatSawTheFailureLateLeavesTheReprocessedDocumentReady() {
        // Dois reenvios de um documento que falhou: B lê o job FAILED e, antes de continuar, A
        // reabre o job e o worker leva o documento a READY. Antes, B gravava QUEUED por cima do
        // READY (ao lado de um job SUCCEEDED) e todo reenvio seguinte devolvia esse QUEUED.
        AtomicBoolean providerDown = new AtomicBoolean(true);
        AtomicReference<Runnable> afterLookup = new AtomicReference<>();
        var jobs = new JobService(new InMemoryJobRepository()) {
            @Override
            public JobRecord findByIdempotencyKey(String tenantId, String type, String key) {
                JobRecord snapshot = super.findByIdempotencyKey(tenantId, type, key);
                Runnable concurrent = afterLookup.getAndSet(null);
                if (concurrent != null) concurrent.run();
                return snapshot;
            }
        };
        Fixture fixture = fixture(new FlakyEmbeddings(providerDown), jobs);
        KnowledgeBase base = fixture.knowledge.createBase("tenant", null, "Docs");
        var first = fixture.knowledge.ingest("tenant", base.id(), store(fixture, "troca.txt"));
        drain(fixture);
        providerDown.set(false);
        afterLookup.set(() -> {
            var winner = fixture.knowledge.ingest("tenant", base.id(), store(fixture, "troca-a.txt"));
            assertThat(winner.outcome()).isEqualTo(KnowledgeService.IngestionOutcome.REPROCESSING);
            drain(fixture);
        });

        var late = fixture.knowledge.ingest("tenant", base.id(), store(fixture, "troca-b.txt"));

        assertThat(fixture.repository.findDocument("tenant", first.document().id()).status())
                .as("o READY gravado pelo worker não é sobrescrito").isEqualTo("READY");
        assertThat(late.outcome()).as("não foi este envio que reabriu o job")
                .isEqualTo(KnowledgeService.IngestionOutcome.ALREADY_PRESENT);
        assertThat(late.job().status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(late.document().status()).isEqualTo("READY");
        try (var worker = fixture.worker()) {
            assertThat(worker.runOnce()).isFalse();
        }
    }

    @Test
    void resubmissionReprocessesFromTheNewFileWhenTheOriginalBlobIsGone() {
        AtomicBoolean providerDown = new AtomicBoolean(true);
        Fixture fixture = fixture(new FlakyEmbeddings(providerDown));
        KnowledgeBase base = fixture.knowledge.createBase("tenant", null, "Docs");
        String original = store(fixture, "troca.txt");
        var first = fixture.knowledge.ingest("tenant", base.id(), original);
        drain(fixture);
        assertThat(fixture.files.delete("tenant", original)).isTrue();

        providerDown.set(false);
        String again = store(fixture, "troca-2.txt");
        var second = fixture.knowledge.ingest("tenant", base.id(), again);
        drain(fixture);

        assertThat(second.outcome()).isEqualTo(KnowledgeService.IngestionOutcome.REPROCESSING);
        assertThat(second.job().payload()).containsEntry("fileId", again);
        assertThat(fixture.repository.findDocument("tenant", first.document().id()))
                .satisfies(document -> {
                    assertThat(document.status()).isEqualTo("READY");
                    assertThat(document.fileId()).isEqualTo(again);
                });
    }

    @Test
    void differentContentStillCreatesItsOwnDocument() {
        Fixture fixture = fixture();
        KnowledgeBase base = fixture.knowledge.createBase("tenant", null, "Docs");
        var first = fixture.knowledge.ingest("tenant", base.id(), store(fixture, "troca.txt"));
        var second = fixture.knowledge.ingest("tenant", base.id(), fixture.files.store(
                "tenant", null, "frete.txt", "text/plain",
                "Frete grátis acima de 200 reais.".getBytes(StandardCharsets.UTF_8)).id());

        assertThat(second.outcome()).isEqualTo(KnowledgeService.IngestionOutcome.QUEUED);
        assertThat(second.document().id()).isNotEqualTo(first.document().id());
        assertThat(second.job().id()).isNotEqualTo(first.job().id());
        assertThat(fixture.repository.listDocuments("tenant", base.id())).hasSize(2);
    }

    /**
     * QA2 P5: o {@code POST /api/jobs} grava o job no tenant de quem submete, mas o handler lia o
     * tenant do payload. Um usuário do tenant B que conhecesse os ids do documento e de outro arquivo
     * do tenant A reescrevia os trechos (e a busca) de A.
     */
    @Test
    void aJobSubmittedByAnotherTenantCannotRewriteTheDocumentOfTheTenantNamedInThePayload() {
        Fixture fixture = fixture();
        KnowledgeBase base = fixture.knowledge.createBase("tenant-a", null, "Docs A");
        String original = fixture.files.store("tenant-a", null, "a1.txt", "text/plain",
                "Arquivo um do tenant A.".getBytes(StandardCharsets.UTF_8)).id();
        String other = fixture.files.store("tenant-a", null, "a2.txt", "text/plain",
                "Arquivo dois do tenant A: conteudo trocado pelo tenant B.".getBytes(StandardCharsets.UTF_8)).id();
        var submission = fixture.knowledge.ingest("tenant-a", base.id(), original);
        drain(fixture);
        String documentId = submission.document().id();
        List<String> before = fixture.repository.listChunks("tenant-a", documentId).stream()
                .map(DocumentChunk::content).toList();

        // O que o controller faz: o tenant do registro é o da requisição (B); o payload é do cliente.
        JobRecord attack = fixture.jobs.submit("tenant-b", null, "DOCUMENT_INGESTION",
                java.util.Map.of("tenantId", "tenant-a", "documentId", documentId, "fileId", other), null, 1);
        drain(fixture);

        assertThat(fixture.jobs.get("tenant-b", attack.id()).status()).isEqualTo(JobStatus.FAILED);
        assertThat(fixture.repository.listChunks("tenant-a", documentId)).extracting(DocumentChunk::content)
                .containsExactlyElementsOf(before)
                .noneMatch(content -> content.contains("trocado"));
        assertThat(fixture.repository.findDocument("tenant-a", documentId).status()).isEqualTo("READY");
        assertThat(fixture.knowledge.search("tenant-a", base.id(), "conteudo trocado pelo tenant B", 3, 0.0))
                .allSatisfy(hit -> assertThat(hit.content()).doesNotContain("trocado"));
    }

    /**
     * Sem tenant no payload, o job roda no tenant do registro: o documento de outro tenant simplesmente
     * não existe ali — a mesma resposta de um id inválido, que não diz a B se o id de A é válido.
     */
    @Test
    void aJobWithoutPayloadTenantRunsInTheTenantOfTheJob() {
        Fixture fixture = fixture();
        KnowledgeBase base = fixture.knowledge.createBase("tenant-a", null, "Docs A");
        String file = fixture.files.store("tenant-a", null, "a1.txt", "text/plain",
                "Arquivo um do tenant A.".getBytes(StandardCharsets.UTF_8)).id();
        var submission = fixture.knowledge.ingest("tenant-a", base.id(), file);
        drain(fixture);
        String documentId = submission.document().id();

        // O próprio tenant reprocessa o documento sem repetir o tenant no payload: vale o do job.
        JobRecord own = fixture.jobs.submit("tenant-a", null, "DOCUMENT_INGESTION",
                java.util.Map.of("documentId", documentId, "fileId", file), null, 1);
        // Outro tenant com os mesmos ids: no tenant dele não há esse documento.
        JobRecord foreign = fixture.jobs.submit("tenant-b", null, "DOCUMENT_INGESTION",
                java.util.Map.of("documentId", documentId, "fileId", file), null, 1);
        drain(fixture);

        assertThat(fixture.jobs.get("tenant-a", own.id()).status()).isEqualTo(JobStatus.SUCCEEDED);
        JobRecord refused = fixture.jobs.get("tenant-b", foreign.id());
        assertThat(refused.status()).isEqualTo(JobStatus.FAILED);
        assertThat(refused.error()).isEqualTo("Document or file not found");
        assertThat(fixture.repository.findDocument("tenant-a", documentId).status()).isEqualTo("READY");
    }

    private static String store(Fixture fixture, String name) {
        return fixture.files.store("tenant", null, name, "text/plain",
                "Trocas em até 7 dias com nota fiscal.".getBytes(StandardCharsets.UTF_8)).id();
    }

    /** Esvazia a fila; o teto cobre as três tentativas de um job que falha. */
    private static void drain(Fixture fixture) {
        try (var worker = fixture.worker()) {
            for (int i = 0; i < 10 && worker.runOnce(); i++) {
                // cada volta processa um job
            }
        }
    }

    /** Provedor de embeddings fora do ar enquanto {@code down} estiver ligado. */
    private record FlakyEmbeddings(AtomicBoolean down) implements KnowledgeEmbeddingModel {
        private static final HashEmbeddingModel DELEGATE = new HashEmbeddingModel(128);

        public float[] embed(String text) {
            if (down.get()) throw new IllegalStateException("embedding provider unavailable");
            return DELEGATE.embed(text);
        }

        public int dimension() {
            return DELEGATE.dimension();
        }
    }

    private Fixture fixture() {
        return fixture(new HashEmbeddingModel(128));
    }

    private Fixture fixture(KnowledgeEmbeddingModel embeddings) {
        return fixture(embeddings, new JobService(new InMemoryJobRepository()));
    }

    private Fixture fixture(KnowledgeEmbeddingModel embeddings, JobService jobs) {
        var files = new FileStorageService(new LocalFileObjectStorage(directory),
                new InMemoryFileMetadataRepository(), 1024 * 1024);
        var repository = new InMemoryKnowledgeRepository();
        var vectorIndex = new InMemoryKnowledgeVectorIndex();
        var knowledge = new KnowledgeService(repository, files, jobs, embeddings, vectorIndex);
        var handler = new DocumentIngestionHandler(
                repository, files, new RecursiveTextChunker(), 70, 10,
                embeddings, vectorIndex, new DocumentTextExtractorRegistry(List.of(
                        new PlainTextDocumentExtractor(),
                        new PdfDocumentExtractor(100),
                        new DocxDocumentExtractor()), 100_000));
        return new Fixture(files, repository, jobs, knowledge, handler, vectorIndex);
    }

    private record Fixture(
            FileStorageService files,
            InMemoryKnowledgeRepository repository,
            JobService jobs,
            KnowledgeService knowledge,
            DocumentIngestionHandler handler,
            InMemoryKnowledgeVectorIndex vectorIndex) {
        JobWorker worker() {
            return new JobWorker("worker", jobs,
                    new JobHandlerRegistry(List.of(handler)), Duration.ofSeconds(5));
        }
    }
}
