package br.com.archflow.api.knowledge;

import br.com.archflow.api.config.GlobalExceptionHandler;
import br.com.archflow.api.config.TenantContext;
import br.com.archflow.api.jobs.InMemoryJobRepository;
import br.com.archflow.api.jobs.JobService;
import br.com.archflow.api.storage.FileStorageService;
import br.com.archflow.api.storage.InMemoryFileMetadataRepository;
import br.com.archflow.api.storage.LocalFileObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * RCHFLW-193: ingerir um documento sem {@code fileId} respondia {@code 500} com o repositório de arquivos
 * em memória (o id nulo estourava NPE no {@code ConcurrentHashMap}) e {@code 404} com o JDBC. Corpo
 * incompleto é erro de quem chama: {@code 400} nos dois, e nenhum documento criado.
 */
@DisplayName("Ingestão sem arquivo: 400, não 500")
class IngestaoSemArquivoTest {

    @TempDir
    Path directory;

    private KnowledgeService knowledge;
    private InMemoryKnowledgeRepository repository;
    private MockMvc mvc;
    private String baseId;

    @BeforeEach
    void monta() {
        var files = new FileStorageService(new LocalFileObjectStorage(directory),
                new InMemoryFileMetadataRepository(), 1024 * 1024);
        repository = new InMemoryKnowledgeRepository();
        knowledge = new KnowledgeService(repository, files, new JobService(new InMemoryJobRepository()),
                new HashEmbeddingModel(16), new InMemoryKnowledgeVectorIndex());
        baseId = knowledge.createBase(TenantContext.DEFAULT_TENANT, null, "Base QA2").id();
        mvc = MockMvcBuilders.standaloneSetup(new SpringKnowledgeController(knowledge))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @ParameterizedTest(name = "corpo {0}")
    @ValueSource(strings = {"{}", "{\"fileId\":null}", "{\"fileId\":\"  \"}"})
    void semFileIdRecusado(String corpo) throws Exception {
        MvcResult r = mvc.perform(post("/api/knowledge-bases/" + baseId + "/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo))
                .andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString()).contains("fileId is required");
        assertThat(knowledge.documents(TenantContext.DEFAULT_TENANT, baseId)).isEmpty();
    }
}
