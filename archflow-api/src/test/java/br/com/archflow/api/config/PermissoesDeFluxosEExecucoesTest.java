package br.com.archflow.api.config;

import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.api.agui.AgUiController;
import br.com.archflow.api.agui.AgUiEventMapper;
import br.com.archflow.api.flow.NodeSupport;
import br.com.archflow.api.flow.WorkflowDeserializer;
import br.com.archflow.api.template.TemplateController;
import br.com.archflow.api.web.template.SpringTemplateController;
import br.com.archflow.api.web.workflow.InMemoryWorkflowRuntimeStore;
import br.com.archflow.api.web.workflow.SpringExecutionController;
import br.com.archflow.api.web.workflow.SpringWorkflowCrudController;
import br.com.archflow.api.web.workflow.SpringWorkflowYamlController;
import br.com.archflow.api.workflow.WorkflowYamlBridge;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.core.StateManager;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.tenant.DefaultTenant;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * RCHFLW-74: as escritas em fluxos e execuções exigem a permissão que o papel built-in declara
 * ({@code Role.createViewerRole} etc.). VIEWER só lê; EXECUTOR lê e executa; DESIGNER cria, edita,
 * apaga, publica e executa; ADMIN/SUPERADMIN faz tudo — inclusive cancelar e retomar execuções, que
 * exigem {@code execution:manage}, declarada só pelo ADMIN.
 *
 * <p>A requisição passa pelos controllers reais e pelos interceptors exatamente como
 * {@link AdminSecurityConfiguration} os registra; os papéis chegam no atributo que o filtro JWT grava.
 */
@DisplayName("Permissões por papel em fluxos e execuções (RCHFLW-74)")
class PermissoesDeFluxosEExecucoesTest {

    private static final String FLUXO = "wf-1";
    private static final String DOC_JSON = "{\"metadata\":{\"name\":\"Triagem\",\"version\":\"1.0.0\"},\"steps\":[]}";
    private static final String YAML = """
            id: wf-1
            metadata:
              name: Triagem
              version: 1.0.0
            steps: []
            """;

    private final ObjectMapper json = new ObjectMapper();
    private InMemoryWorkflowRuntimeStore store;
    private String execucao;
    /** O store em memória já nasce com um fluxo de demonstração; conta-se a partir daqui. */
    private int fluxosAntes;

    private MockMvc mvc(boolean authEnabled) {
        FlowEngine engine = mock(FlowEngine.class);
        // Nunca termina: a execução fica RUNNING, o que deixa o cancel exercitável.
        when(engine.execute(any(), any())).thenReturn(new CompletableFuture<>());
        WorkflowDeserializer deserializer = doc -> mock(Flow.class);
        StateManager stateManager = mock(StateManager.class);
        FlowRepository flowRepository = mock(FlowRepository.class);
        var crud = new SpringWorkflowCrudController(store, deserializer, engine, flowRepository, null, null);
        var execucoes = new SpringExecutionController(store, engine, stateManager, null);
        var yaml = new SpringWorkflowYamlController(store, new WorkflowYamlBridge());
        var agUi = new AgUiController(store, deserializer, engine, mock(EventStreamRegistry.class),
                mock(AgUiEventMapper.class), stateManager, flowRepository, json);
        // Instalar um template grava um fluxo em rascunho no store: é a mesma ação de criar.
        TemplateController templateDelegate = mock(TemplateController.class);
        when(templateDelegate.installTemplate(any(), any())).thenReturn(Map.of("templateId", "customer-support"));
        var templates = new SpringTemplateController(templateDelegate, store, mock(NodeSupport.class));
        // Os interceptors como a configuração de produção os registra (padrões de path incluídos).
        RegistroExposto registro = new RegistroExposto();
        new AdminSecurityConfiguration(authEnabled).addInterceptors(registro);
        return MockMvcBuilders.standaloneSetup(crud, execucoes, yaml, agUi, templates)
                .addInterceptors(registro.interceptors().stream()
                        .map(HandlerInterceptor.class::cast)
                        .toArray(HandlerInterceptor[]::new))
                .build();
    }

    @BeforeEach
    void setUp() {
        store = new InMemoryWorkflowRuntimeStore();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", FLUXO);
        doc.put("metadata", Map.of("name", "Triagem", "version", "1.0.0"));
        doc.put("steps", List.of());
        doc.put("status", "draft");
        store.putWorkflow(DefaultTenant.ID, FLUXO, doc);
        execucao = String.valueOf(store.createExecution(DefaultTenant.ID, FLUXO, "Triagem").get("id"));
        fluxosAntes = store.workflows(DefaultTenant.ID).size();
    }

    // ── requisições ─────────────────────────────────────────────────────────

    private enum Acao {
        CRIAR(HttpMethod.POST, "/api/workflows", DOC_JSON, "workflow:create"),
        EDITAR(HttpMethod.PUT, "/api/workflows/" + FLUXO, DOC_JSON, "workflow:update"),
        EDITAR_YAML(HttpMethod.PUT, "/api/workflows/" + FLUXO + "/yaml",
                "{\"id\":\"wf-1\",\"yaml\":" + quoted(YAML) + "}", "workflow:update"),
        MUDAR_STATUS(HttpMethod.PATCH, "/api/workflows/" + FLUXO + "/status", "{\"status\":\"active\"}",
                "workflow:update"),
        PUBLICAR(HttpMethod.POST, "/api/workflows/" + FLUXO + "/publish", "{}", "workflow:update"),
        IMPLANTAR(HttpMethod.PUT, "/api/workflows/" + FLUXO + "/deployments/PRODUCTION",
                "{\"versionId\":\"v-x\"}", "workflow:update"),
        APAGAR(HttpMethod.DELETE, "/api/workflows/" + FLUXO, null, "workflow:delete"),
        EXECUTAR(HttpMethod.POST, "/api/workflows/" + FLUXO + "/execute", "{}", "workflow:execute"),
        EXECUTAR_AG_UI(HttpMethod.POST, "/ag-ui/workflows/" + FLUXO, "{}", "workflow:execute"),
        INSTALAR_TEMPLATE(HttpMethod.POST, "/api/templates/customer-support/install", "{\"name\":\"x\"}",
                "workflow:create"),
        CANCELAR(HttpMethod.POST, "/api/executions/{exec}/cancel", null, "execution:manage"),
        RETOMAR(HttpMethod.POST, "/api/executions/{exec}/resume", null, "execution:manage");

        final HttpMethod method;
        final String path;
        final String body;
        final String permission;

        Acao(HttpMethod method, String path, String body, String permission) {
            this.method = method;
            this.path = path;
            this.body = body;
            this.permission = permission;
        }
    }

    private static String quoted(String text) {
        return "\"" + text.replace("\n", "\\n") + "\"";
    }

    private MvcResult faz(MockMvc mvc, Acao acao, List<String> roles) throws Exception {
        MockHttpServletRequestBuilder req = request(acao.method, acao.path.replace("{exec}", execucao));
        if (acao.body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(acao.body);
        }
        if (roles != null) {
            req.requestAttr(JwtAuthenticationFilter.ATTR_ROLES, roles);
        }
        return mvc.perform(req).andReturn();
    }

    private MvcResult le(MockMvc mvc, String path, List<String> roles) throws Exception {
        return mvc.perform(request(HttpMethod.GET, path).requestAttr(JwtAuthenticationFilter.ATTR_ROLES, roles))
                .andReturn();
    }

    private void assertNegado(MvcResult result, Acao acao) throws Exception {
        assertThat(result.getResponse().getStatus()).as(acao.name()).isEqualTo(403);
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("error").asText()).isEqualTo("FORBIDDEN");
        assertThat(body.path("permission").asText()).as(acao.name()).isEqualTo(acao.permission);
        assertThat(body.path("message").asText()).contains(acao.permission);
    }

    private void assertPermitido(MvcResult result, Acao acao) {
        assertThat(result.getResponse().getStatus()).as(acao.name()).isNotEqualTo(403);
    }

    private void assertFluxoIntacto() {
        Map<String, Object> wf = store.getWorkflow(DefaultTenant.ID, FLUXO);
        assertThat(wf).as("o fluxo não pode ter sido apagado nem alterado").isNotNull();
        assertThat(wf.get("status")).isEqualTo("draft");
        assertThat(store.workflowVersions(DefaultTenant.ID, FLUXO)).isEmpty();
        assertThat(store.workflows(DefaultTenant.ID)).as("nenhum fluxo criado").hasSize(fluxosAntes);
        assertThat(store.executions(DefaultTenant.ID, FLUXO, null)).hasSize(1);
    }

    // ── papéis ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("VIEWER")
    class Viewer {

        @Test
        @DisplayName("lê fluxos e execuções")
        void le() throws Exception {
            MockMvc mvc = mvc(true);
            List<String> viewer = List.of("VIEWER");
            assertThat(PermissoesDeFluxosEExecucoesTest.this.le(mvc, "/api/workflows", viewer)
                    .getResponse().getStatus()).isEqualTo(200);
            assertThat(PermissoesDeFluxosEExecucoesTest.this.le(mvc, "/api/workflows/" + FLUXO, viewer)
                    .getResponse().getStatus()).isEqualTo(200);
            assertThat(PermissoesDeFluxosEExecucoesTest.this.le(mvc, "/api/workflows/" + FLUXO + "/yaml", viewer)
                    .getResponse().getStatus()).isEqualTo(200);
            assertThat(PermissoesDeFluxosEExecucoesTest.this.le(mvc, "/api/executions", viewer)
                    .getResponse().getStatus()).isEqualTo(200);
            assertThat(PermissoesDeFluxosEExecucoesTest.this.le(mvc, "/api/executions/" + execucao, viewer)
                    .getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("não cria, edita, apaga, publica, executa, cancela nem retoma: 403 dizendo a permissão")
        void naoEscreve() throws Exception {
            MockMvc mvc = mvc(true);
            for (Acao acao : Acao.values()) {
                assertNegado(faz(mvc, acao, List.of("VIEWER")), acao);
            }
            assertFluxoIntacto();
        }
    }

    @Nested
    @DisplayName("EXECUTOR")
    class Executor {

        @Test
        @DisplayName("executa (REST e AG-UI)")
        void executa() throws Exception {
            MockMvc mvc = mvc(true);
            MvcResult rest = faz(mvc, Acao.EXECUTAR, List.of("EXECUTOR"));
            assertThat(rest.getResponse().getStatus()).isEqualTo(200);
            assertPermitido(faz(mvc, Acao.EXECUTAR_AG_UI, List.of("EXECUTOR")), Acao.EXECUTAR_AG_UI);
        }

        @Test
        @DisplayName("não cria, edita, apaga nem publica; não cancela nem retoma")
        void naoEdita() throws Exception {
            MockMvc mvc = mvc(true);
            for (Acao acao : List.of(Acao.CRIAR, Acao.EDITAR, Acao.EDITAR_YAML, Acao.MUDAR_STATUS, Acao.PUBLICAR,
                    Acao.IMPLANTAR, Acao.APAGAR, Acao.INSTALAR_TEMPLATE, Acao.CANCELAR, Acao.RETOMAR)) {
                assertNegado(faz(mvc, acao, List.of("EXECUTOR")), acao);
            }
            assertFluxoIntacto();
        }
    }

    @Nested
    @DisplayName("DESIGNER (editor)")
    class Designer {

        @Test
        @DisplayName("cria, edita, muda status, publica, executa e apaga")
        void editaEExecuta() throws Exception {
            MockMvc mvc = mvc(true);
            List<String> designer = List.of("ROLE_DESIGNER");
            assertThat(faz(mvc, Acao.CRIAR, designer).getResponse().getStatus()).isEqualTo(201);
            assertThat(faz(mvc, Acao.INSTALAR_TEMPLATE, designer).getResponse().getStatus()).isEqualTo(201);
            assertThat(faz(mvc, Acao.EDITAR, designer).getResponse().getStatus()).isEqualTo(200);
            assertThat(faz(mvc, Acao.EDITAR_YAML, designer).getResponse().getStatus()).isEqualTo(200);
            assertThat(faz(mvc, Acao.MUDAR_STATUS, designer).getResponse().getStatus()).isEqualTo(200);
            assertThat(faz(mvc, Acao.PUBLICAR, designer).getResponse().getStatus()).isEqualTo(201);
            assertThat(faz(mvc, Acao.EXECUTAR, designer).getResponse().getStatus()).isEqualTo(200);
            assertThat(faz(mvc, Acao.APAGAR, designer).getResponse().getStatus()).isEqualTo(204);
            assertThat(store.hasWorkflow(DefaultTenant.ID, FLUXO)).isFalse();
        }

        @Test
        @DisplayName("não cancela nem retoma: execution:manage não é declarada pelo DESIGNER")
        void naoGerenciaExecucao() throws Exception {
            MockMvc mvc = mvc(true);
            assertNegado(faz(mvc, Acao.CANCELAR, List.of("DESIGNER")), Acao.CANCELAR);
            assertNegado(faz(mvc, Acao.RETOMAR, List.of("DESIGNER")), Acao.RETOMAR);
            assertThat(store.getExecution(DefaultTenant.ID, execucao).get("status")).isEqualTo("RUNNING");
        }
    }

    @Nested
    @DisplayName("ADMIN / SUPERADMIN")
    class Admin {

        @Test
        @DisplayName("faz tudo, inclusive cancelar a execução")
        void fazTudo() throws Exception {
            MockMvc mvc = mvc(true);
            for (Acao acao : Acao.values()) {
                if (acao == Acao.APAGAR || acao == Acao.CANCELAR) {
                    continue; // por último: apagar/cancelar mudam o estado que as outras ações usam
                }
                assertPermitido(faz(mvc, acao, List.of("ADMIN")), acao);
            }
            MvcResult cancel = faz(mvc, Acao.CANCELAR, List.of("superadmin"));
            assertThat(cancel.getResponse().getStatus()).isEqualTo(200);
            assertThat(store.getExecution(DefaultTenant.ID, execucao).get("status")).isEqualTo("CANCELLED");
            assertThat(faz(mvc, Acao.APAGAR, List.of("ROLE_ADMIN")).getResponse().getStatus()).isEqualTo(204);
        }
    }

    @Test
    @DisplayName("token sem papel ou com papel desconhecido não concede escrita")
    void semPapelNaoEscreve() throws Exception {
        MockMvc mvc = mvc(true);
        assertNegado(faz(mvc, Acao.APAGAR, null), Acao.APAGAR);
        assertNegado(faz(mvc, Acao.APAGAR, List.of()), Acao.APAGAR);
        assertNegado(faz(mvc, Acao.EXECUTAR, List.of("EDITOR")), Acao.EXECUTAR);
        assertNegado(faz(mvc, Acao.EXECUTAR, List.of("TENANT_ADMIN")), Acao.EXECUTAR);
        assertFluxoIntacto();
    }

    @Test
    @DisplayName("auth desligada (dev): sem checagem de papel, como no AdminRoleInterceptor")
    void authDesligada() throws Exception {
        MockMvc mvc = mvc(false);
        assertThat(faz(mvc, Acao.APAGAR, List.of("VIEWER")).getResponse().getStatus()).isEqualTo(204);
        assertThat(faz(mvc, Acao.CRIAR, null).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    @DisplayName("preflight OPTIONS passa sem papel (CORS)")
    void preflight() throws Exception {
        MockMvc mvc = mvc(true);
        MvcResult result = mvc.perform(request(HttpMethod.OPTIONS, "/api/workflows/" + FLUXO)).andReturn();
        assertThat(result.getResponse().getStatus()).isNotEqualTo(403);
    }

    /** Expõe os interceptors registrados ({@code getInterceptors} é protegido). */
    private static final class RegistroExposto extends InterceptorRegistry {
        List<Object> interceptors() {
            return getInterceptors();
        }
    }
}
