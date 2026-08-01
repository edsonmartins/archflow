package br.com.archflow.api.knowledge;

import br.com.archflow.api.jobs.*;
import br.com.archflow.api.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

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

    private Fixture fixture() {
        var files = new FileStorageService(new LocalFileObjectStorage(directory),
                new InMemoryFileMetadataRepository(), 1024 * 1024);
        var repository = new InMemoryKnowledgeRepository();
        var jobs = new JobService(new InMemoryJobRepository());
        var embeddings = new HashEmbeddingModel(128);
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
