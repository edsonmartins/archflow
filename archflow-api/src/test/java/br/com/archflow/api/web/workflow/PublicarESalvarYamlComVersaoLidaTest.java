package br.com.archflow.api.web.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import br.com.archflow.api.config.GlobalExceptionHandler;
import br.com.archflow.api.flow.WorkflowDeserializer;
import br.com.archflow.api.workflow.WorkflowYamlBridge;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.persistence.FlowRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * As outras duas rotas que escrevem a partir do rascunho também conferem a versão lida, como o PUT do JSON desde
 * o RCHFLW-125.
 *
 * <ul>
 *   <li>RCHFLW-174: o Salvar YAML da aba Código não mandava versão nenhuma, e um YAML aberto antes de outra
 *       sessão salvar apagava esse salvamento em silêncio (200);</li>
 *   <li>RCHFLW-146: o Publicar punha em PRODUCTION o rascunho que estivesse gravado — o editor agora salva antes e
 *       publica com o {@code updatedAt} do que salvou; se outra sessão gravou no meio, 409 e nenhuma versão.</li>
 * </ul>
 *
 * <p>Por HTTP (MockMvc): o que importa é o cabeçalho que o Spring liga ao parâmetro.</p>
 */
@DisplayName("Salvar YAML e Publicar com a versão lida (RCHFLW-174, RCHFLW-146)")
class PublicarESalvarYamlComVersaoLidaTest {

    private final ObjectMapper json = new ObjectMapper();
    private InMemoryWorkflowRuntimeStore store;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        store = new InMemoryWorkflowRuntimeStore();
        var crud = new SpringWorkflowCrudController(store, mock(WorkflowDeserializer.class),
                mock(FlowEngine.class), mock(FlowRepository.class), null, null);
        var yaml = new SpringWorkflowYamlController(store, new WorkflowYamlBridge());
        mvc = MockMvcBuilders.standaloneSetup(crud, yaml).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    @DisplayName("YAML lido antes de outra sessão salvar → 409 WORKFLOW_CONFLICT, e o salvamento da outra fica")
    void yamlDesatualizadoDaConflito() throws Exception {
        String id = cria("Saída");
        Map<String, Object> lido = corpo(mvc.perform(get("/api/workflows/" + id + "/yaml")).andReturn());
        assertThat(lido.get("updatedAt")).as("o GET do YAML diz de que versão o texto saiu").isNotNull();

        // Sessão A salva pelo canvas, com a versão que leu.
        Map<String, Object> doc = corpo(mvc.perform(get("/api/workflows/" + id)).andReturn());
        int salvarA = mvc.perform(json(put("/api/workflows/" + id)
                        .header("If-Match", "\"" + doc.get("updatedAt") + "\""), documento("Saída revisada pela A")))
                .andReturn().getResponse().getStatus();
        assertThat(salvarA).isEqualTo(200);

        // Sessão B salva o YAML que leu antes.
        MvcResult yamlB = mvc.perform(json(put("/api/workflows/" + id + "/yaml")
                        .header("If-Match", "\"" + lido.get("updatedAt") + "\""),
                        Map.of("id", id, "yaml", lido.get("yaml"))))
                .andReturn();

        assertThat(yamlB.getResponse().getStatus()).isEqualTo(409);
        assertThat(corpo(yamlB).get("code")).isEqualTo("WORKFLOW_CONFLICT");
        assertThat(nome(store.getWorkflow(null, id))).isEqualTo("Saída revisada pela A");
    }

    @Test
    @DisplayName("YAML na versão atual grava e devolve o updatedAt novo; sem If-Match grava como sempre")
    void yamlNaVersaoAtualGrava() throws Exception {
        String id = cria("Fluxo");
        Map<String, Object> lido = corpo(mvc.perform(get("/api/workflows/" + id + "/yaml")).andReturn());
        String yaml = String.valueOf(lido.get("yaml")).replace("Fluxo", "Fluxo pelo YAML");

        MvcResult salvo = mvc.perform(json(put("/api/workflows/" + id + "/yaml")
                .header("If-Match", "\"" + lido.get("updatedAt") + "\""), Map.of("id", id, "yaml", yaml))).andReturn();
        assertThat(salvo.getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> resposta = corpo(salvo);
        assertThat(resposta.get("updatedAt")).isNotNull().isNotEqualTo(lido.get("updatedAt"))
                .isEqualTo(store.getWorkflow(null, id).get("updatedAt"));
        assertThat(nome(store.getWorkflow(null, id))).isEqualTo("Fluxo pelo YAML");

        // Scripts (e o "Sobrescrever" do aviso de conflito) mandam sem versão.
        int semVersao = mvc.perform(json(put("/api/workflows/" + id + "/yaml"), Map.of("id", id, "yaml", yaml)))
                .andReturn().getResponse().getStatus();
        assertThat(semVersao).isEqualTo(200);
    }

    @Test
    @DisplayName("Publicar com a versão de antes de outra gravação → 409, sem versão criada nem implantada")
    void publicarRascunhoQueMudouDaConflito() throws Exception {
        String id = cria("v1");
        Map<String, Object> lido = corpo(mvc.perform(get("/api/workflows/" + id)).andReturn());
        corpo(mvc.perform(json(put("/api/workflows/" + id), documento("outra sessão"))).andReturn());

        MvcResult publicar = mvc.perform(json(post("/api/workflows/" + id + "/publish")
                .header("If-Match", "\"" + lido.get("updatedAt") + "\""), Map.of("comment", "v1"))).andReturn();

        assertThat(publicar.getResponse().getStatus()).isEqualTo(409);
        assertThat(corpo(publicar).get("code")).isEqualTo("WORKFLOW_CONFLICT");
        assertThat(store.workflowVersions(null, id)).isEmpty();
        assertThat(store.getDeployment(null, id, "PRODUCTION")).isNull();
    }

    @Test
    @DisplayName("Publicar com a versão atual → 201 com o documento dessa versão em PRODUCTION")
    void publicarNaVersaoAtual() throws Exception {
        String id = cria("rascunho");
        Map<String, Object> salvo = corpo(mvc.perform(json(put("/api/workflows/" + id), documento("salvo")))
                .andReturn());

        MvcResult publicar = mvc.perform(json(post("/api/workflows/" + id + "/publish")
                .header("If-Match", "\"" + salvo.get("updatedAt") + "\""), Map.of("comment", "v1"))).andReturn();

        assertThat(publicar.getResponse().getStatus()).isEqualTo(201);
        var versoes = store.workflowVersions(null, id);
        assertThat(versoes).hasSize(1);
        assertThat(nome(versoes.get(0).document())).isEqualTo("salvo");
        assertThat(store.getDeployment(null, id, "PRODUCTION").versionId()).isEqualTo(versoes.get(0).id());
    }

    // ── helpers ──────────────────────────────────────────────────

    private String cria(String nome) throws Exception {
        return String.valueOf(corpo(mvc.perform(json(post("/api/workflows"), documento(nome))).andReturn())
                .get("id"));
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder pedido, Map<String, ?> corpo)
            throws Exception {
        return pedido.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(corpo));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> corpo(MvcResult resultado) throws Exception {
        return json.readValue(resultado.getResponse().getContentAsString(), Map.class);
    }

    private static Map<String, Object> documento(String nome) {
        return Map.of("metadata", Map.of("name", nome, "version", "1.0.0"),
                "steps", List.of(Map.of("id", "out", "type", "output", "label", "Saída")),
                "configuration", Map.of());
    }

    @SuppressWarnings("unchecked")
    private static String nome(Map<String, Object> doc) {
        return String.valueOf(((Map<String, Object>) doc.get("metadata")).get("name"));
    }
}
