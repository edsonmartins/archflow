package br.com.archflow.api.config;

import br.com.archflow.api.agent.AgentController;
import br.com.archflow.api.agent.qp.QpAgentController;
import br.com.archflow.api.agent.qp.QpAgentService;
import br.com.archflow.api.agent.stream.InvocacaoEmFluxo;
import br.com.archflow.api.agent.stream.InvocacaoEmFluxoController;
import br.com.archflow.api.agent.vendax.VendaxAgentDispatcher;
import br.com.archflow.api.agent.vendax.VendaxInvokeController;
import br.com.archflow.api.agui.AgUiAgentController;
import br.com.archflow.api.approval.ApprovalController;
import br.com.archflow.api.brainsentry.BrainSentryConfigController;
import br.com.archflow.api.conversation.ConversationController;
import br.com.archflow.api.conversation.ConversationReplyService;
import br.com.archflow.api.conversation.ConversationResumeService;
import br.com.archflow.api.events.EventController;
import br.com.archflow.api.jobs.JobService;
import br.com.archflow.api.jobs.SpringJobController;
import br.com.archflow.api.knowledge.KnowledgeService;
import br.com.archflow.api.knowledge.SpringKnowledgeController;
import br.com.archflow.api.linktor.LinktorConfigController;
import br.com.archflow.api.linktor.LinktorHttpClient;
import br.com.archflow.api.mcp.server.SpringMcpServerController;
import br.com.archflow.api.mcp.server.WorkflowMcpServer;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.api.skills.SkillsController;
import br.com.archflow.api.storage.FileStorageService;
import br.com.archflow.api.storage.SpringFileController;
import br.com.archflow.api.triggers.ScheduledTriggerController;
import br.com.archflow.api.web.agent.SpringAgentController;
import br.com.archflow.api.web.approval.SpringApprovalController;
import br.com.archflow.api.web.brainsentry.SpringBrainSentryConfigController;
import br.com.archflow.api.web.conversation.SpringConversationController;
import br.com.archflow.api.web.events.SpringEventController;
import br.com.archflow.api.web.linktor.SpringLinktorConfigController;
import br.com.archflow.api.web.linktor.SpringLinktorEscalationController;
import br.com.archflow.api.web.linktor.SpringLinktorInboxController;
import br.com.archflow.api.web.orchestration.SpringDynamicWorkflowController;
import br.com.archflow.api.web.skills.SpringSkillsController;
import br.com.archflow.api.web.triggers.SpringScheduledTriggerController;
import br.com.archflow.conversation.form.FormRegistry;
import br.com.archflow.conversation.service.ConversationService;
import br.com.archflow.conversation.state.SuspendedConversation;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.model.config.ResolvedLLMConfig;
import br.com.archflow.model.escalation.EscalationChannel;
import br.com.archflow.model.security.ApiKeyScope;
import br.com.archflow.model.security.Role;
import br.com.archflow.model.security.User;
import br.com.archflow.model.tenant.DefaultTenant;
import br.com.archflow.security.apikey.ApiKeyService;
import br.com.archflow.security.auth.InMemoryUserRepository;
import br.com.archflow.security.jwt.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * QA2 P1–P4: as escritas que estavam só com autenticação passam a exigir a permissão do papel, no
 * mesmo modelo de {@link PermissoesDeFluxosEExecucoesTest} — decidir aprovações, configurar o Linktor e o
 * Brain Sentry, o servidor {@code /mcp}, os endpoints que acionam agente/LLM e os que criam gatilhos,
 * bases, arquivos, jobs e conversas.
 *
 * <p>A requisição passa pelo filtro JWT real (token assinado com os papéis no claim, ou chave de API),
 * pelos interceptors exatamente como {@link AdminSecurityConfiguration} os registra e pelos controllers
 * reais; só o que está atrás deles é dublê. "Negado" quer dizer {@code 403} com o corpo de sempre
 * <em>e</em> nenhuma chamada ao que está atrás do controller — o teste não confia só no status.
 */
@DisplayName("Permissões por papel nas demais escritas (QA2 P1–P4)")
class PermissoesDasDemaisEscritasTest {

    private static final String SECRET = "test-secret-key-256-bits-long-enough-for-hs256-ok";
    private static final String CHAVE_DO_CORE = "chave-de-servico-do-core";

    private final JwtService jwtService = new JwtService(SECRET);
    private final ObjectMapper json = new ObjectMapper();
    private ApiKeyService apiKeys;
    private InMemoryUserRepository users;

    private ApprovalController approvals;
    private WorkflowMcpServer mcp;
    private AgentController agents;
    private EventController events;
    private DynamicWorkflowService orchestration;
    private QpAgentService qp;
    private ScheduledTriggerController triggers;
    private KnowledgeService knowledge;
    private FileStorageService files;
    private JobService jobs;
    private SkillsController skills;
    private LinktorConfigController linktor;
    private BrainSentryConfigController brainSentry;
    private LinktorHttpClient linktorInbox;
    private EscalationChannel escalation;
    private LLMConfigResolver llm;
    private ConversationService conversations;
    private ConversationResumeService resume;
    private ConversationReplyService reply;
    private VendaxAgentDispatcher vendax;
    private InvocacaoEmFluxo invocacaoEmFluxo;
    private MockMvc mvcAtual;

    /** Qualquer exceção depois do controller vira 500: o que se mede aqui é se a requisição chegou lá. */
    @RestControllerAdvice
    static class QualquerFalha {
        @ExceptionHandler(Exception.class)
        ResponseEntity<Map<String, String>> falha(Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    /** Sonda no caminho de execução REST: conta quantas requisições chegaram ao handler. */
    @RestController
    static class SondaDeExecucao {
        final AtomicInteger chegadas = new AtomicInteger();

        @PostMapping("/api/workflows/{id}/execute")
        Map<String, String> executa(@PathVariable String id) {
            chegadas.incrementAndGet();
            return Map.of("workflowId", id);
        }
    }

    private SondaDeExecucao sonda;

    @BeforeEach
    void monta() {
        sonda = new SondaDeExecucao();
        users = new InMemoryUserRepository();
        apiKeys = new ApiKeyService(new InMemoryApiKeyRepository(), users);
        approvals = mock(ApprovalController.class);
        mcp = mock(WorkflowMcpServer.class);
        agents = mock(AgentController.class);
        events = mock(EventController.class);
        orchestration = mock(DynamicWorkflowService.class);
        qp = mock(QpAgentService.class);
        triggers = mock(ScheduledTriggerController.class);
        knowledge = mock(KnowledgeService.class);
        files = mock(FileStorageService.class);
        jobs = mock(JobService.class);
        skills = mock(SkillsController.class);
        linktor = mock(LinktorConfigController.class);
        brainSentry = mock(BrainSentryConfigController.class);
        linktorInbox = mock(LinktorHttpClient.class);
        escalation = mock(EscalationChannel.class);
        llm = mock(LLMConfigResolver.class);
        conversations = mock(ConversationService.class);
        resume = mock(ConversationResumeService.class);
        reply = mock(ConversationReplyService.class);
        vendax = mock(VendaxAgentDispatcher.class);
        invocacaoEmFluxo = mock(InvocacaoEmFluxo.class);
    }

    private MockMvc mvc(boolean authEnabled) {
        TenantScope scope = new TenantScope(authEnabled);
        var conversa = new SpringConversationController(mock(ConversationController.class), conversations, reply,
                resume, mock(FormRegistry.class));
        // As três leem a propriedade por @Value em campo; aqui não há contexto Spring para injetá-la.
        ReflectionTestUtils.setField(conversa, "authEnabled", authEnabled);
        var invoke = new VendaxInvokeController(vendax);
        ReflectionTestUtils.setField(invoke, "invokeKey", CHAVE_DO_CORE);
        var invokeEmFluxo = new InvocacaoEmFluxoController(invocacaoEmFluxo);
        ReflectionTestUtils.setField(invokeEmFluxo, "chaveDeServico", CHAVE_DO_CORE);

        RegistroExposto registro = new RegistroExposto();
        new AdminSecurityConfiguration(authEnabled).addInterceptors(registro);
        return MockMvcBuilders.standaloneSetup(
                        new SpringApprovalController(approvals, scope),
                        new SpringMcpServerController(mcp),
                        new SpringAgentController(agents, scope),
                        new SpringEventController(events, scope),
                        new AgUiAgentController(llm, mock(ResolvedLLMConfig.class), json),
                        new SpringDynamicWorkflowController(orchestration, scope),
                        new QpAgentController(qp, authEnabled),
                        new SpringScheduledTriggerController(triggers, scope),
                        new SpringKnowledgeController(knowledge),
                        new SpringFileController(files),
                        new SpringJobController(jobs),
                        new SpringSkillsController(skills),
                        new SpringLinktorConfigController(linktor),
                        new SpringBrainSentryConfigController(brainSentry),
                        new SpringLinktorInboxController(linktorInbox),
                        new SpringLinktorEscalationController(escalation),
                        conversa, invoke, invokeEmFluxo, sonda)
                .setControllerAdvice(new QualquerFalha())
                .addFilters(new JwtAuthenticationFilter(jwtService, null, apiKeys, authEnabled, ""),
                        new ImpersonationFilter(false))
                .addInterceptors(registro.interceptors().stream()
                        .map(HandlerInterceptor.class::cast)
                        .toArray(HandlerInterceptor[]::new))
                .build();
    }

    private String jwt(String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("type", "access");
        claims.put("roles", List.of(role));
        claims.put("tenantId", DefaultTenant.ID);
        return jwtService.generateToken("u-" + role.toLowerCase(), role.toLowerCase() + "@qa2.local", 60_000,
                claims);
    }

    private MvcResult como(String role, AbstractMockHttpServletRequestBuilder<?> req) throws Exception {
        return mvcAtual.perform(req.header("Authorization", "Bearer " + jwt(role))).andReturn();
    }

    private static AbstractMockHttpServletRequestBuilder<?> corpo(AbstractMockHttpServletRequestBuilder<?> req,
                                                                  String body) {
        return req.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** 403 do {@link WorkflowPermissionInterceptor}, nomeando a permissão que faltou. */
    private void assertNegado(MvcResult result, String permissao, String quem) throws Exception {
        String rota = result.getRequest().getMethod() + " " + result.getRequest().getRequestURI() + " (" + quem
                + ")";
        assertThat(result.getResponse().getStatus()).as(rota).isEqualTo(403);
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("error").asText()).as(rota).isEqualTo("FORBIDDEN");
        assertThat(body.path("permission").asText()).as(rota).isEqualTo(permissao);
        assertThat(body.path("message").asText()).as(rota).contains(permissao);
    }

    /** 403 do {@link AdminRoleInterceptor}: o corpo de administração de sempre. */
    private void assertSoAdmin(MvcResult result, String quem) throws Exception {
        String rota = result.getRequest().getMethod() + " " + result.getRequest().getRequestURI() + " (" + quem
                + ")";
        assertThat(result.getResponse().getStatus()).as(rota).isEqualTo(403);
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("error").asText()).as(rota).isEqualTo("FORBIDDEN");
        assertThat(body.path("message").asText()).as(rota).isEqualTo("Administration endpoints require the ADMIN role");
    }

    private static void assertPassou(MvcResult result) throws Exception {
        String rota = result.getRequest().getMethod() + " " + result.getRequest().getRequestURI() + " → "
                + result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).as(rota).isNotIn(401, 403);
    }

    private static void assertChamado(Object servico) {
        assertThat(mockingDetails(servico).getInvocations())
                .as("a requisição precisa ter chegado a %s",
                        mockingDetails(servico).getMockCreationSettings().getTypeToMock().getSimpleName())
                .isNotEmpty();
    }

    private static final String DECISAO = "{\"tenantId\":\"__default__\",\"decision\":\"APPROVED\","
            + "\"comment\":\"QA2 viewer aprovando\"}";
    private static final String EDICAO = "{\"tenantId\":\"__default__\",\"decision\":\"EDITED\","
            + "\"editedPayload\":{\"valor\":9999.99}}";

    // ── P1 ───────────────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("P1 — decidir uma aprovação exige execution:manage")
    class Aprovacoes {

        @Test
        @DisplayName("VIEWER, EXECUTOR e DESIGNER não aprovam, rejeitam nem editam, por nenhuma das duas rotas")
        void naoDecidem() throws Exception {
            mvcAtual = mvc(true);
            for (String papel : List.of("VIEWER", "EXECUTOR", "DESIGNER")) {
                for (String rota : List.of("/api/approvals/req-1", "/archflow/approvals/req-1")) {
                    assertNegado(como(papel, corpo(post(rota), DECISAO)), "execution:manage", papel);
                    assertNegado(como(papel, corpo(post(rota), EDICAO)), "execution:manage", papel);
                }
            }
            verifyNoInteractions(approvals);
        }

        @Test
        @DisplayName("ADMIN decide; o VIEWER continua lendo a fila")
        void adminDecideViewerLe() throws Exception {
            mvcAtual = mvc(true);
            assertPassou(como("ADMIN", corpo(post("/api/approvals/req-1"), DECISAO)));
            verify(approvals).submitDecision(eq("req-1"), any(), any());

            assertThat(como("VIEWER", get("/api/approvals/pending")).getResponse().getStatus()).isEqualTo(200);
            assertThat(como("VIEWER", get("/api/approvals/pending/count")).getResponse().getStatus())
                    .isEqualTo(200);
        }

        private void conversaSuspensa(String execucao) {
            SuspendedConversation estado = SuspendedConversation.builder()
                    .conversationId("conv-1")
                    .resumeToken("tok-1")
                    .workflowId(execucao == null ? null : "wf-1")
                    .workflowExecutionId(execucao)
                    .context(Map.of(SpringConversationController.TENANT_METADATA, DefaultTenant.ID))
                    .build();
            when(conversations.getByToken("tok-1")).thenReturn(Optional.of(estado));
            when(conversations.getById("conv-1")).thenReturn(Optional.of(estado));
        }

        private static final String ENVIO = "{\"resumeToken\":\"tok-1\",\"formData\":{\"valor\":9999.99}}";

        @Test
        @DisplayName("o formulário do chat que continua uma execução é o mesmo portão: execution:manage")
        void formularioDoChat() throws Exception {
            conversaSuspensa("exec-1");
            mvcAtual = mvc(true);
            // VIEWER já para no interceptor (conversar exige workflow:execute).
            assertNegado(como("VIEWER", corpo(post("/api/conversations/resume"), ENVIO)), "workflow:execute",
                    "VIEWER");
            // EXECUTOR conversa, mas não decide o portão da execução.
            assertNegado(como("EXECUTOR", corpo(post("/api/conversations/resume"), ENVIO)), "execution:manage",
                    "EXECUTOR");
            assertNegado(como("DESIGNER", corpo(post("/api/conversations/resume"), ENVIO)), "execution:manage",
                    "DESIGNER");
            verifyNoInteractions(resume);

            assertPassou(como("ADMIN", corpo(post("/api/conversations/resume"), ENVIO)));
            verify(resume).resume(any(), eq(DefaultTenant.ID), any());
        }

        @Test
        @DisplayName("sem execução ligada, o envio só pede a resposta do agente: workflow:execute basta")
        void formularioSemExecucao() throws Exception {
            conversaSuspensa(null);
            mvcAtual = mvc(true);
            assertPassou(como("EXECUTOR", corpo(post("/api/conversations/resume"), ENVIO)));
            verify(resume).resume(any(), eq(DefaultTenant.ID), any());
        }
    }

    // ── P2 ───────────────────────────────────────────────────────────────────────────────────────

    /** Corpos completos: os DTOs são records com campos primitivos, e um primitivo ausente é 400. */
    private static final String LINKTOR = "{\"enabled\":true,\"apiBaseUrl\":\"http://127.0.0.1:9/qa2\","
            + "\"apiKey\":\"\",\"accessToken\":\"\",\"mcpCommand\":\"python3 -c print(1)\",\"timeoutSeconds\":5}";
    private static final String BRAIN_SENTRY = "{\"enabled\":true,\"baseUrl\":\"http://127.0.0.1:9/qa2\","
            + "\"apiKey\":\"\",\"tenantId\":\"__default__\",\"maxTokenBudget\":100,"
            + "\"deepAnalysisEnabled\":false,\"timeoutSeconds\":5}";

    @Nested
    @DisplayName("P2 — Linktor e Brain Sentry são administração")
    class Integracoes {

        @Test
        @DisplayName("quem não é ADMIN não lê nem grava a configuração (mcpCommand, URL e chave), nem usa a inbox")
        void naoAdmin() throws Exception {
            mvcAtual = mvc(true);
            for (String papel : List.of("VIEWER", "EXECUTOR", "DESIGNER")) {
                assertSoAdmin(como(papel, corpo(put("/api/admin/linktor"), LINKTOR)), papel);
                assertSoAdmin(como(papel, get("/api/admin/linktor")), papel);
                assertSoAdmin(como(papel, get("/api/admin/linktor/inbox/conversations")), papel);
                assertSoAdmin(como(papel, corpo(post("/api/admin/linktor/inbox/conversations/c-1/messages"),
                        "{\"text\":\"oi\"}")), papel);
                assertSoAdmin(como(papel, corpo(post("/api/admin/linktor/escalate"), "{\"conversationId\":\"c\"}")),
                        papel);
                assertSoAdmin(como(papel, corpo(put("/api/admin/brainsentry"), BRAIN_SENTRY)),
                        papel);
                assertSoAdmin(como(papel, get("/api/admin/brainsentry")), papel);
            }
            verifyNoInteractions(linktor, linktorInbox, escalation, brainSentry);
        }

        @Test
        @DisplayName("ADMIN configura e usa a inbox")
        void admin() throws Exception {
            mvcAtual = mvc(true);
            assertPassou(como("ADMIN", corpo(put("/api/admin/linktor"), LINKTOR)));
            verify(linktor).update(any());
            assertPassou(como("SUPERADMIN", get("/api/admin/linktor/inbox/conversations")));
            assertChamado(linktorInbox);
            assertPassou(como("ADMIN", corpo(put("/api/admin/brainsentry"), BRAIN_SENTRY)));
            verify(brainSentry).update(any());
        }
    }

    // ── P3 ───────────────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("P3 — o servidor /mcp exige workflow:execute, como a regra da chave de API")
    class Mcp {

        private static final String TOOLS_CALL = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"workflow_wf-1\",\"arguments\":{\"pedido\":\"R-VIEWER-MCP\"}}}";

        @Test
        @DisplayName("VIEWER não executa fluxo pelo tools/call (nem lista)")
        void viewer() throws Exception {
            mvcAtual = mvc(true);
            assertNegado(como("VIEWER", corpo(post("/mcp"), TOOLS_CALL)), "workflow:execute", "VIEWER");
            String toolsList = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
            assertNegado(como("VIEWER", corpo(post("/mcp"), toolsList)), "workflow:execute", "VIEWER");
            verifyNoInteractions(mcp);
        }

        @Test
        @DisplayName("EXECUTOR executa")
        void executor() throws Exception {
            mvcAtual = mvc(true);
            assertPassou(como("EXECUTOR", corpo(post("/mcp"), TOOLS_CALL)));
            verify(mcp, timeout(5_000)).handleRequest(any(), eq(DefaultTenant.ID));
        }

        @Test
        @DisplayName("chave de API com workflow:execute continua chegando ao /mcp (cliente de máquina)")
        void chaveDeApi() throws Exception {
            usuario("dono", Role.createExecutorRole());
            String chave = apiKeys.createApiKey(DefaultTenant.ID, "dono", "mcp",
                    Set.of(ApiKeyScope.WORKFLOW_EXECUTE), null).credential();
            mvcAtual = mvc(true);
            MvcResult result = mvcAtual.perform(corpo(post("/mcp"), TOOLS_CALL)
                    .header("Authorization", "Bearer " + chave)).andReturn();
            assertPassou(result);
            verify(mcp, timeout(5_000)).handleRequest(any(), eq(DefaultTenant.ID));
        }
    }

    @Nested
    @DisplayName("Chave de API: os escopos dela valem no lugar dos papéis, que ela não tem")
    class ChaveDeApi {

        private String chave(Set<ApiKeyScope> escopos) {
            usuario("dono", Role.createExecutorRole());
            return apiKeys.createApiKey(DefaultTenant.ID, "dono", "ci", escopos, null).credential();
        }

        @Test
        @DisplayName("workflow:execute executa pelo REST; o interceptor não a trata como um token sem papel")
        void executa() throws Exception {
            String credencial = chave(Set.of(ApiKeyScope.WORKFLOW_EXECUTE));
            mvcAtual = mvc(true);
            MvcResult result = mvcAtual.perform(post("/api/workflows/wf-1/execute")
                    .header("Authorization", "Bearer " + credencial)).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(sonda.chegadas.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("só workflow:read não executa: 403 (do filtro, pelo escopo) e o handler não roda")
        void soLeitura() throws Exception {
            String credencial = chave(Set.of(ApiKeyScope.WORKFLOW_READ));
            mvcAtual = mvc(true);
            MvcResult result = mvcAtual.perform(post("/api/workflows/wf-1/execute")
                    .header("Authorization", "Bearer " + credencial)).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(403);
            assertThat(sonda.chegadas.get()).isZero();
        }
    }

    // ── P4 ───────────────────────────────────────────────────────────────────────────────────────

    private record Escrita(AbstractMockHttpServletRequestBuilder<?> req, String permissao) {
    }

    private static Escrita escrita(AbstractMockHttpServletRequestBuilder<?> req, String body, String permissao) {
        return new Escrita(body == null ? req : corpo(req, body), permissao);
    }

    private static AbstractMockHttpServletRequestBuilder<?> upload() {
        return multipart("/api/files").file(new MockMultipartFile("file", "a.txt", "text/plain", "oi".getBytes()));
    }

    @Nested
    @DisplayName("P4 — acionar agente/LLM e criar gatilhos, bases, arquivos, jobs e conversas")
    class DemaisEscritas {

        private List<Escrita> escritas() {
            return List.of(
                    escrita(post("/archflow/agents/support/invoke"), "{\"tenantId\":\"__default__\",\"payload\":{}}",
                            "workflow:execute"),
                    escrita(post("/archflow/events/message"),
                            "{\"tenantId\":\"__default__\",\"agentId\":\"support\",\"message\":\"oi\"}",
                            "workflow:execute"),
                    escrita(post("/ag-ui/agent"), "{}", "workflow:execute"),
                    escrita(post("/api/orchestration/run"), "{\"goal\":\"x\"}", "workflow:execute"),
                    escrita(post("/api/agents/qp/quote"), "{\"entrada\":\"x\"}", "workflow:execute"),
                    escrita(post("/api/conversations"), null, "workflow:execute"),
                    escrita(post("/api/conversations/c-1/messages"), "{\"content\":\"oi\"}", "workflow:execute"),
                    escrita(delete("/api/conversations/c-1"), null, "workflow:execute"),
                    escrita(post("/api/admin/triggers/trg-1/fire"), null, "workflow:execute"),
                    escrita(post("/api/admin/triggers"), "{\"workflowId\":\"wf-1\",\"cron\":\"0 * * * * ?\"}",
                            "workflow:update"),
                    escrita(put("/api/admin/triggers/trg-1"), "{\"workflowId\":\"wf-1\"}", "workflow:update"),
                    escrita(delete("/api/admin/triggers/trg-1"), null, "workflow:update"),
                    escrita(post("/api/knowledge-bases"), "{\"name\":\"KB\"}", "workflow:update"),
                    escrita(post("/api/knowledge-bases/kb-1/documents"), "{\"fileId\":\"f-1\"}", "workflow:update"),
                    escrita(put("/api/knowledge-bases/kb-1/index/active"), "{\"version\":1}", "workflow:update"),
                    new Escrita(upload(), "workflow:update"),
                    escrita(delete("/api/files/f-1"), null, "workflow:update"),
                    escrita(post("/api/jobs"), "{\"type\":\"DOCUMENT_INGESTION\",\"payload\":{}}", "execution:manage"),
                    escrita(post("/api/jobs/job-1/cancel"), null, "execution:manage"),
                    escrita(post("/api/admin/skills/s-1/activate"), null, "agent:manage"),
                    escrita(delete("/api/admin/skills/s-1/activate"), null, "agent:manage"));
        }

        @Test
        @DisplayName("VIEWER: toda escrita é 403 nomeando a permissão, e nada chega ao serviço")
        void viewer() throws Exception {
            mvcAtual = mvc(true);
            for (Escrita e : escritas()) {
                assertNegado(como("VIEWER", e.req()), e.permissao(), "VIEWER");
            }
            verifyNoInteractions(agents, events, llm, orchestration, qp, triggers, knowledge, files, jobs, skills,
                    reply);
        }

        @Test
        @DisplayName("o papel que declara a permissão passa (EXECUTOR executa, DESIGNER configura, ADMIN gere)")
        void papelLegitimo() throws Exception {
            mvcAtual = mvc(true);
            for (Escrita e : escritas()) {
                String papel = switch (e.permissao()) {
                    case "workflow:execute" -> "EXECUTOR";
                    case "workflow:update" -> "DESIGNER";
                    default -> "ADMIN";
                };
                assertPassou(como(papel, e.req()));
            }
            for (Object servico : List.of(agents, events, llm, orchestration, qp, triggers, knowledge, files, jobs,
                    skills)) {
                assertChamado(servico);
            }
        }

        @Test
        @DisplayName("EXECUTOR não configura gatilhos, bases, arquivos, jobs nem skills")
        void executorNaoConfigura() throws Exception {
            mvcAtual = mvc(true);
            for (Escrita e : escritas()) {
                if (!"workflow:execute".equals(e.permissao())) {
                    assertNegado(como("EXECUTOR", e.req()), e.permissao(), "EXECUTOR");
                }
            }
            verifyNoInteractions(knowledge, files, jobs, skills);
        }

        @Test
        @DisplayName("leituras (e a busca da base, um POST que só lê) seguem abertas ao VIEWER")
        void leituras() throws Exception {
            mvcAtual = mvc(true);
            for (String rota : List.of("/api/jobs", "/api/knowledge-bases", "/api/files", "/api/admin/triggers",
                    "/api/admin/skills", "/api/conversations")) {
                assertThat(como("VIEWER", get(rota)).getResponse().getStatus()).as(rota).isEqualTo(200);
            }
            assertPassou(como("VIEWER", corpo(post("/api/knowledge-bases/kb-1/search"), "{\"query\":\"x\"}")));
            assertChamado(knowledge);
        }
    }

    // ── caminho percent-encoded ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Caminho percent-encoded: a regra casa o mesmo caminho decodificado que o roteamento")
    class CaminhoCodificado {

        private static final String TOOLS_LIST = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";

        @Test
        @DisplayName("VIEWER com uma letra escapada (%61 = a) continua negado e nada chega ao serviço")
        void viewer() throws Exception {
            mvcAtual = mvc(true);
            assertNegado(como("VIEWER", corpo(post(URI.create("/api/approv%61ls/req-1")), DECISAO)),
                    "execution:manage", "VIEWER");
            assertNegado(como("VIEWER", corpo(post(URI.create("/archflow/%61pprovals/req-1")), DECISAO)),
                    "execution:manage", "VIEWER");
            assertNegado(como("VIEWER", corpo(post(URI.create("/%6Dcp")), TOOLS_LIST)), "workflow:execute",
                    "VIEWER");
            assertNegado(como("VIEWER", corpo(post(URI.create("/ag-ui/%61gent")), "{}")), "workflow:execute",
                    "VIEWER");
            assertNegado(como("VIEWER", corpo(post(URI.create("/api/%6Frchestration/run")), "{\"goal\":\"x\"}")),
                    "workflow:execute", "VIEWER");
            assertNegado(como("VIEWER", corpo(post(URI.create("/api/%6Aobs")),
                    "{\"type\":\"DOCUMENT_INGESTION\",\"payload\":{}}")), "execution:manage", "VIEWER");
            // A regra anterior (RCHFLW-74) tinha o mesmo furo.
            assertNegado(como("VIEWER", post(URI.create("/api/workfl%6Fws/wf-1/execute"))), "workflow:execute",
                    "VIEWER");
            verifyNoInteractions(approvals, mcp, llm, orchestration, jobs);
            assertThat(sonda.chegadas.get()).isZero();
        }

        @Test
        @DisplayName("sem token, /%61pi/... (que o filtro JWT não reconhece como protegido) é 403: sem papel")
        void anonimo() throws Exception {
            mvcAtual = mvc(true);
            assertNegado(mvcAtual.perform(corpo(post(URI.create("/%61pi/approvals/req-1")), DECISAO)).andReturn(),
                    "execution:manage", "anônimo");
            String toolsCall = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"workflow_wf-1\",\"arguments\":{}}}";
            assertNegado(mvcAtual.perform(corpo(post(URI.create("/%6Dcp")), toolsCall)).andReturn(),
                    "workflow:execute", "anônimo");
            verifyNoInteractions(approvals, mcp);
        }

        @Test
        @DisplayName("o papel que tem a permissão continua passando pelo caminho escapado")
        void papelLegitimo() throws Exception {
            mvcAtual = mvc(true);
            assertPassou(como("ADMIN", corpo(post(URI.create("/api/approv%61ls/req-1")), DECISAO)));
            verify(approvals).submitDecision(eq("req-1"), any(), any());
        }
    }

    // ── máquina e dev ────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("VendaX Core: o invoke continua autenticado só pela chave de serviço")
    class VendaxCore {

        private static final String INVOKE = "{\"tenantId\":\"t-1\",\"agent\":\"qp\",\"conversationId\":\"c-1\"}";

        private MvcResult comChaveDoCore(AbstractMockHttpServletRequestBuilder<?> req) throws Exception {
            return mvcAtual.perform(req.header("Authorization", "Bearer " + CHAVE_DO_CORE)).andReturn();
        }

        @Test
        @DisplayName("invoke, invoke/stream e cancel chegam ao controller com a autenticação ligada")
        void chegam() throws Exception {
            mvcAtual = mvc(true);
            MvcResult invoke = comChaveDoCore(corpo(post("/api/agents/invoke"), INVOKE));
            assertThat(invoke.getResponse().getStatus()).isEqualTo(202);
            verify(vendax).dispatch(any());

            // Sem definição do tipo FLUXO o próprio controller recusa com 400 — prova que a requisição
            // passou do filtro e dos interceptors.
            MvcResult stream = comChaveDoCore(corpo(post("/api/agents/invoke/stream"), INVOKE));
            assertThat(stream.getResponse().getStatus()).isEqualTo(400);
            assertThat(stream.getResponse().getContentAsString()).contains("FLUXO");

            MvcResult cancel = comChaveDoCore(post("/api/agents/invoke/k-1/cancel"));
            assertThat(cancel.getResponse().getStatus()).isEqualTo(404);
            verify(invocacaoEmFluxo).cancelar("k-1");
        }

        @Test
        @DisplayName("sem a chave de serviço continua 401 (o gate é o do controller)")
        void semChave() throws Exception {
            mvcAtual = mvc(true);
            MvcResult result = mvcAtual.perform(corpo(post("/api/agents/invoke"), INVOKE)
                    .header("Authorization", "Bearer outra")).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(401);
            verifyNoInteractions(vendax);
        }
    }

    @Test
    @DisplayName("auth desligada (dev): nada muda — sem token, as escritas chegam aos serviços")
    void authDesligada() throws Exception {
        mvcAtual = mvc(false);
        assertPassou(mvcAtual.perform(corpo(post("/api/approvals/req-1"), DECISAO)).andReturn());
        verify(approvals).submitDecision(eq("req-1"), any(), any());
        assertPassou(mvcAtual.perform(corpo(put("/api/admin/linktor"), LINKTOR)).andReturn());
        verify(linktor).update(any());
        assertPassou(mvcAtual.perform(corpo(post("/api/jobs"), "{\"type\":\"X\",\"payload\":{}}")).andReturn());
        assertChamado(jobs);
        assertPassou(mvcAtual.perform(corpo(post("/mcp"),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")).andReturn());
        verify(mcp, timeout(5_000)).handleRequest(any(), any());
        assertPassou(mvcAtual.perform(corpo(post("/archflow/agents/support/invoke"),
                "{\"tenantId\":\"__default__\",\"payload\":{}}")).andReturn());
        assertChamado(agents);
    }

    private User usuario(String id, Role role) {
        User user = new User();
        user.setId(id);
        user.setUsername(id);
        user.setEmail(id + "@example.com");
        user.setEnabled(true);
        user.addRole(role);
        return users.save(user);
    }

    /** Expõe os interceptors registrados ({@code getInterceptors} é protegido). */
    private static final class RegistroExposto extends InterceptorRegistry {
        List<Object> interceptors() {
            return getInterceptors();
        }
    }
}
