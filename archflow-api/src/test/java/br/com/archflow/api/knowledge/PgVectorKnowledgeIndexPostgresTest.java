package br.com.archflow.api.knowledge;

import br.com.archflow.api.jobs.JdbcJobRepository;
import br.com.archflow.api.jobs.JobHandlerRegistry;
import br.com.archflow.api.jobs.JobService;
import br.com.archflow.api.jobs.JobWorker;
import br.com.archflow.api.storage.FileStorageService;
import br.com.archflow.api.storage.JdbcFileMetadataRepository;
import br.com.archflow.api.storage.LocalFileObjectStorage;
import br.com.archflow.api.storage.StoredFile;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link PgVectorKnowledgeIndex} contra PostgreSQL + pgvector reais, com as migrations Flyway do
 * archflow-api como estão no repositório.
 *
 * <p>Existe por causa do RCHFLW-110: o índice gravava {@code created_at}/{@code activated_at} com
 * {@code setObject(Instant)}, que o pgjdbc recusa, e toda ingestão com JDBC ligado falhava. Os testes
 * de ingestão usam o índice em memória, então nada pegou. Cada teste usa tenant e base próprios,
 * porque o container é um só para a classe.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Índice de conhecimento em pgvector (PostgreSQL real)")
class PgVectorKnowledgeIndexPostgresTest {

    private static final int DIMENSAO = 128;

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static PGSimpleDataSource dataSource;

    @TempDir
    Path diretorio;

    @BeforeAll
    static void migrar() {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }

    @Test
    @DisplayName("candidata sem versão ativa: BUILDING, sem base, created_at persistido")
    void candidataSemVersaoAtiva() {
        var index = new PgVectorKnowledgeIndex(dataSource, DIMENSAO);
        String tenant = tenant();
        KnowledgeBase base = base(tenant);

        var candidata = index.createCandidate(tenant, base.id());

        assertThat(candidata.status()).isEqualTo("BUILDING");
        assertThat(candidata.basedOnVersionId()).isNull();
        assertThat(index.activeVersion(tenant, base.id())).isNull();
        assertThat(index.versions(tenant, base.id())).singleElement().satisfies(v -> {
            assertThat(v.id()).isEqualTo(candidata.id());
            assertThat(v.status()).isEqualTo("BUILDING");
            assertThat(v.createdAt()).isCloseTo(candidata.createdAt(), within(1, ChronoUnit.MILLIS));
            assertThat(v.activatedAt()).isNull();
        });
    }

    @Test
    @DisplayName("replaceDocument troca os chunks do documento, a ativação grava activated_at e a busca acha")
    void substituicaoEAtivacao() {
        var index = new PgVectorKnowledgeIndex(dataSource, DIMENSAO);
        String tenant = tenant();
        KnowledgeBase base = base(tenant);
        var candidata = index.createCandidate(tenant, base.id());

        index.replaceDocument(tenant, base.id(), candidata.id(), "doc-a",
                List.of(chunk(tenant, "doc-a", "c-velho", 0, "texto antigo")));
        index.replaceDocument(tenant, base.id(), candidata.id(), "doc-a",
                List.of(chunk(tenant, "doc-a", "c-1", 0, "trocas em 7 dias"),
                        chunk(tenant, "doc-a", "c-2", 1, "frete grátis")));
        Instant antes = Instant.now().minusSeconds(5);

        assertThat(index.activate(tenant, base.id(), candidata.id())).isTrue();

        var ativa = index.activeVersion(tenant, base.id());
        assertThat(ativa.id()).isEqualTo(candidata.id());
        assertThat(ativa.status()).isEqualTo("ACTIVE");
        assertThat(ativa.activatedAt()).isAfter(antes);
        var acertos = index.search(tenant, base.id(), null, eixo(0), 10, -1.0);
        assertThat(acertos).extracting(KnowledgeVectorIndex.SearchHit::chunkId)
                .as("a segunda substituição apagou o chunk antigo")
                .containsExactly("c-1", "c-2");
        assertThat(acertos.getFirst().score()).isCloseTo(1.0, within(1e-6));
        assertThat(index.search(tenant, base.id(), null, eixo(0), 10, 0.5))
                .extracting(KnowledgeVectorIndex.SearchHit::chunkId).containsExactly("c-1");
    }

    @Test
    @DisplayName("candidata com versão ativa copia os embeddings dela, e a ativação aposenta a anterior")
    void candidataComVersaoAtivaCopia() {
        var index = new PgVectorKnowledgeIndex(dataSource, DIMENSAO);
        String tenant = tenant();
        KnowledgeBase base = base(tenant);
        var v1 = index.createCandidate(tenant, base.id());
        index.replaceDocument(tenant, base.id(), v1.id(), "doc-a",
                List.of(chunk(tenant, "doc-a", "a-1", 0, "política de trocas")));
        assertThat(index.activate(tenant, base.id(), v1.id())).isTrue();

        var v2 = index.createCandidate(tenant, base.id());
        var atrasada = index.createCandidate(tenant, base.id());

        assertThat(v2.basedOnVersionId()).isEqualTo(v1.id());
        assertThat(index.search(tenant, base.id(), v2.id(), eixo(0), 10, -1.0))
                .as("a candidata nasce com a cópia da versão ativa")
                .extracting(KnowledgeVectorIndex.SearchHit::chunkId).containsExactly("a-1");
        index.replaceDocument(tenant, base.id(), v2.id(), "doc-b",
                List.of(chunk(tenant, "doc-b", "b-1", 1, "prazo de entrega")));
        assertThat(index.activate(tenant, base.id(), v2.id())).isTrue();
        assertThat(index.activate(tenant, base.id(), atrasada.id()))
                .as("candidata baseada numa versão que deixou de ser a ativa")
                .isFalse();

        assertThat(index.activeVersion(tenant, base.id()).id()).isEqualTo(v2.id());
        assertThat(index.versions(tenant, base.id()))
                .filteredOn(v -> v.id().equals(v1.id())).singleElement()
                .satisfies(v -> assertThat(v.status()).isEqualTo("INACTIVE"));
        assertThat(index.search(tenant, base.id(), null, eixo(0), 10, -1.0))
                .extracting(KnowledgeVectorIndex.SearchHit::documentId)
                .containsExactlyInAnyOrder("doc-a", "doc-b");
        assertThat(index.search(tenant, base.id(), v1.id(), eixo(0), 10, -1.0))
                .as("a versão anterior não foi tocada pela candidata")
                .extracting(KnowledgeVectorIndex.SearchHit::chunkId).containsExactly("a-1");
    }

    @Test
    @DisplayName("busca e ativação filtram por tenant")
    void filtroPorTenant() {
        var index = new PgVectorKnowledgeIndex(dataSource, DIMENSAO);
        String acme = tenant();
        String globex = tenant();
        KnowledgeBase baseAcme = base(acme);
        KnowledgeBase baseGlobex = base(globex);
        var versaoAcme = ativarComUmChunk(index, acme, baseAcme, "acme-1");
        ativarComUmChunk(index, globex, baseGlobex, "globex-1");

        assertThat(index.search(acme, baseAcme.id(), null, eixo(0), 10, -1.0))
                .extracting(KnowledgeVectorIndex.SearchHit::chunkId).containsExactly("acme-1");
        assertThat(index.search(globex, baseAcme.id(), null, eixo(0), 10, -1.0))
                .as("outro tenant não enxerga a base pelo id").isEmpty();
        assertThat(index.search(globex, baseAcme.id(), versaoAcme.id(), eixo(0), 10, -1.0)).isEmpty();
        assertThat(index.versions(globex, baseAcme.id())).isEmpty();
        assertThatThrownBy(() -> index.activate(globex, baseAcme.id(), versaoAcme.id()))
                .hasMessage("Failed to activate pgvector index version: Knowledge base not found");
    }

    @Test
    @DisplayName("a falha do driver aparece na mensagem, sem o SQL do lote")
    void causaDoDriverNaMensagem() {
        var index = new PgVectorKnowledgeIndex(dataSource, DIMENSAO);
        String tenant = tenant();
        KnowledgeBase base = base(tenant);
        var candidata = index.createCandidate(tenant, base.id());

        assertThatThrownBy(() -> index.replaceDocument(tenant, base.id(), candidata.id(), "doc-a",
                List.of(chunk(tenant, "doc-a", "repetido", 0, "conteúdo sigiloso"),
                        chunk(tenant, "doc-a", "repetido", 1, "conteúdo sigiloso"))))
                .hasMessageStartingWith("Failed to replace pgvector document index: ")
                .hasMessageContaining("knowledge_embeddings_pkey")
                .satisfies(e -> assertThat(e.getMessage())
                        .doesNotContainIgnoringCase("insert")
                        .doesNotContain("conteúdo sigiloso"))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
    }

    @Test
    @DisplayName("o Detail do Postgres (linha recusada, com o conteúdo) não entra na mensagem")
    void detalheDoServidorForaDaMensagem() {
        var index = new PgVectorKnowledgeIndex(dataSource, DIMENSAO);
        String tenant = tenant();
        KnowledgeBase base = base(tenant);
        var candidata = index.createCandidate(tenant, base.id());

        // document_id nulo viola o NOT NULL; o Postgres responde com
        // "Detail: Failing row contains (..., conteúdo sigiloso, ...)" e o pgjdbc o põe na mensagem.
        assertThatThrownBy(() -> index.replaceDocument(tenant, base.id(), candidata.id(), null,
                List.of(chunk(tenant, null, "linha-nula", 0, "conteúdo sigiloso"))))
                .hasMessageStartingWith("Failed to replace pgvector document index: ")
                .hasMessageContaining("document_id")
                .hasMessageContaining("not-null")
                .satisfies(e -> assertThat(e.getMessage())
                        .doesNotContain("conteúdo sigiloso")
                        .doesNotContain("Failing row")
                        .doesNotContain("\n"))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
    }

    @Test
    @DisplayName("ingestão ponta a ponta com todos os stores JDBC deixa o documento READY e buscável")
    void ingestaoComJdbc() {
        var fixture = fixture(DIMENSAO);
        String tenant = tenant();
        StoredFile arquivo = fixture.files.store(tenant, null, "troca.txt", "text/plain",
                "Trocas em até 7 dias com nota fiscal.".getBytes(StandardCharsets.UTF_8));
        KnowledgeBase base = fixture.knowledge.createBase(tenant, null, "Políticas da loja");
        var envio = fixture.knowledge.ingest(tenant, base.id(), arquivo.id());

        fixture.processarFila();

        KnowledgeDocument documento = fixture.repository.findDocument(tenant, envio.document().id());
        assertThat(documento.error()).isNull();
        assertThat(documento.status()).isEqualTo("READY");
        assertThat(fixture.knowledge.search(tenant, base.id(), "nota fiscal", 3, -1.0))
                .extracting(KnowledgeVectorIndex.SearchHit::documentId)
                .containsExactly(documento.id());
    }

    @Test
    @DisplayName("ingestão que o banco recusa grava no documento a causa do driver")
    void ingestaoFalhaComCausa() {
        // Índice e embeddings de 64 dimensões contra a coluna vector(128) da migration: a checagem
        // em Java passa e quem recusa é o pgvector, dentro do lote.
        var fixture = fixture(64);
        String tenant = tenant();
        StoredFile arquivo = fixture.files.store(tenant, null, "troca.txt", "text/plain",
                "Trocas em até 7 dias.".getBytes(StandardCharsets.UTF_8));
        KnowledgeBase base = fixture.knowledge.createBase(tenant, null, "Políticas");
        var envio = fixture.knowledge.ingest(tenant, base.id(), arquivo.id());

        fixture.processarFila();

        KnowledgeDocument documento = fixture.repository.findDocument(tenant, envio.document().id());
        assertThat(documento.status()).isEqualTo("FAILED");
        assertThat(documento.error())
                .startsWith("Failed to replace pgvector document index: ")
                .contains("expected 128 dimensions, not 64")
                .doesNotContainIgnoringCase("insert");
    }

    private KnowledgeVectorIndex.IndexVersion ativarComUmChunk(
            PgVectorKnowledgeIndex index, String tenant, KnowledgeBase base, String chunkId) {
        var versao = index.createCandidate(tenant, base.id());
        index.replaceDocument(tenant, base.id(), versao.id(), "doc",
                List.of(chunk(tenant, "doc", chunkId, 0, chunkId)));
        assertThat(index.activate(tenant, base.id(), versao.id())).isTrue();
        return versao;
    }

    private static String tenant() {
        return "t-" + UUID.randomUUID();
    }

    private static KnowledgeBase base(String tenant) {
        return new JdbcKnowledgeRepository(dataSource).saveBase(new KnowledgeBase(
                "kb-" + UUID.randomUUID(), tenant, null, "Base", Instant.now()));
    }

    private static KnowledgeVectorIndex.EmbeddedChunk chunk(
            String tenant, String documento, String id, int eixo, String conteudo) {
        return new KnowledgeVectorIndex.EmbeddedChunk(
                new DocumentChunk(id, tenant, documento, eixo, conteudo, 0, conteudo.length()), eixo(eixo));
    }

    private static float[] eixo(int posicao) {
        float[] vetor = new float[DIMENSAO];
        vetor[posicao] = 1f;
        return vetor;
    }

    private Fixture fixture(int dimensao) {
        var files = new FileStorageService(new LocalFileObjectStorage(diretorio),
                new JdbcFileMetadataRepository(dataSource), 1024 * 1024);
        var repository = new JdbcKnowledgeRepository(dataSource);
        var jobs = new JobService(new JdbcJobRepository(dataSource));
        var embeddings = new HashEmbeddingModel(dimensao);
        var vectorIndex = new PgVectorKnowledgeIndex(dataSource, dimensao);
        var knowledge = new KnowledgeService(repository, files, jobs, embeddings, vectorIndex);
        var handler = new DocumentIngestionHandler(repository, files, new RecursiveTextChunker(), 70, 10,
                embeddings, vectorIndex, new DocumentTextExtractorRegistry(
                        List.of(new PlainTextDocumentExtractor()), 100_000));
        return new Fixture(files, repository, jobs, knowledge, handler);
    }

    private record Fixture(FileStorageService files, JdbcKnowledgeRepository repository, JobService jobs,
                           KnowledgeService knowledge, DocumentIngestionHandler handler) {
        /**
         * A fila é a mesma tabela para a classe toda: um job que falhou noutro teste volta como
         * QUEUED e pode ser o primeiro a ser reivindicado. Esvaziar a fila (com teto, porque o
         * job que falha é tentado de novo até {@code max_attempts}) não depende da ordem.
         */
        void processarFila() {
            try (var worker = new JobWorker("worker-" + UUID.randomUUID(), jobs,
                    new JobHandlerRegistry(List.of(handler)), Duration.ofSeconds(5))) {
                for (int i = 0; i < 10 && worker.runOnce(); i++) {
                    // cada volta processa um job
                }
            }
        }
    }
}
