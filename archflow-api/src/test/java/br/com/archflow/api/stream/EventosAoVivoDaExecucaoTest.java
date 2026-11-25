package br.com.archflow.api.stream;

import br.com.archflow.agent.persistence.InMemoryFlowRepository;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.agent.streaming.RunningFlowsRegistry;
import br.com.archflow.api.audit.AuditTrail;
import br.com.archflow.api.config.ImpersonationFilter;
import br.com.archflow.api.config.JwtAuthenticationFilter;
import br.com.archflow.api.flow.FlowEngineFactory;
import br.com.archflow.api.flow.InMemoryStateManager;
import br.com.archflow.api.flow.SimpleFlow;
import br.com.archflow.api.flow.SimpleStepResult;
import br.com.archflow.api.flow.WorkflowDeserializer;
import br.com.archflow.api.web.workflow.InMemoryWorkflowRuntimeStore;
import br.com.archflow.api.web.workflow.SpringWorkflowCrudController;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.FlowMetadata;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.model.flow.StepType;
import br.com.archflow.observability.audit.InMemoryAuditRepository;
import br.com.archflow.security.jwt.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RCHFLW-50: a tela de eventos ao vivo recebe os eventos de uma execução assinando
 * {@code GET /api/stream/{executionId}} — o canal {@code <tenant da requisição>:<executionId>}.
 *
 * <p>Antes o motor publicava só no canal nu {@code executionId} e no administrativo, e o stream só
 * abre {@code tenant:sessão}: a tela conectava, mostrava {@code connected} e nada mais. O teste
 * percorre o caminho inteiro com as peças reais — token assinado → filtros → {@code POST /execute}
 * cria a execução com o tenant do token → motor real → listener → SSE — e lê o que chegou na
 * resposta do stream. Afirmar só o registry provaria a chave, não a entrega.</p>
 */
@DisplayName("Eventos ao vivo de uma execução no canal tenant:execução")
class EventosAoVivoDaExecucaoTest {

    private static final String SECRET = "test-secret-key-256-bits-long-enough-for-hs256-ok";
    private static final String WORKFLOW_ID = "wf-ao-vivo";

    private final JwtService jwtService = new JwtService(SECRET);
    private EventStreamRegistry registry;
    private InMemoryWorkflowRuntimeStore store;
    private SpringWorkflowCrudController crud;
    private MockMvc mvc;
    /** O primeiro passo só termina quando o teste já assinou o stream. */
    private CountDownLatch liberar;

    @BeforeEach
    void monta() {
        registry = new EventStreamRegistry();
        FlowRepository flowRepository = new InMemoryFlowRepository();
        FlowEngine engine = FlowEngineFactory.create(flowRepository, registry, new RunningFlowsRegistry(),
                new InMemoryStateManager());
        store = new InMemoryWorkflowRuntimeStore();
        liberar = new CountDownLatch(1);
        WorkflowDeserializer deserializer = json -> new SimpleFlow(String.valueOf(json.get("id")),
                new FlowMetadata("Ao vivo", "", "1.0.0", null, null, List.of()),
                List.of(passo("espera", "fim", true), passo("fim", null, false)));
        crud = new SpringWorkflowCrudController(store, deserializer, engine, flowRepository,
                new AuditTrail(InMemoryAuditRepository::new), null);
        mvc = MockMvcBuilders.standaloneSetup(new SpringStreamController(registry))
                .addFilters(jwt(), new ImpersonationFilter(true))
                .build();
    }

    @AfterEach
    void desmonta() {
        liberar.countDown();
        RequestContextHolder.resetRequestAttributes();
        registry.shutdown();
    }

    private JwtAuthenticationFilter jwt() {
        return new JwtAuthenticationFilter(jwtService, null, true, "");
    }

    private FlowStep passo(String id, String proximo, boolean espera) {
        return new FlowStep() {
            @Override public String getId() { return id; }
            @Override public StepType getType() { return StepType.TOOL; }
            @Override public List<StepConnection> getConnections() {
                return proximo == null ? List.of() : List.of(edge(id, proximo));
            }
            @Override public CompletableFuture<StepResult> execute(ExecutionContext c) {
                if (espera) {
                    try {
                        assertThat(liberar.await(10, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return CompletableFuture.completedFuture(SimpleStepResult.ok(id, "ok", 1));
            }
        };
    }

    private static StepConnection edge(String from, String to) {
        return new StepConnection() {
            @Override public String getSourceId() { return from; }
            @Override public String getTargetId() { return to; }
            @Override public Optional<String> getCondition() { return Optional.empty(); }
            @Override public boolean isErrorPath() { return false; }
        };
    }

    private String token(String tenantId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("type", "access");
        claims.put("roles", List.of("editor"));
        claims.put("tenantId", tenantId);
        return jwtService.generateToken("user-1", "alice", 60_000, claims);
    }

    /** {@code POST /api/workflows/{id}/execute} com o token, depois dos mesmos filtros do servidor. */
    private String executarComo(String tenantId) throws Exception {
        store.putWorkflow(tenantId, WORKFLOW_ID, new HashMap<>(Map.of(
                "id", WORKFLOW_ID, "metadata", Map.of("name", "Ao vivo"))));
        MockHttpServletRequest req = new MockHttpServletRequest("POST",
                "/api/workflows/" + WORKFLOW_ID + "/execute");
        req.addHeader("Authorization", "Bearer " + token(tenantId));
        AtomicReference<String> executionId = new AtomicReference<>();
        MockHttpServletResponse resp = new MockHttpServletResponse();
        jwt().doFilter(req, resp, (rq, rs) -> new ImpersonationFilter(true).doFilter(rq, rs, (r2, s2) -> {
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
            try {
                executionId.set(String.valueOf(crud.execute(WORKFLOW_ID, null).getBody().get("executionId")));
            } finally {
                RequestContextHolder.resetRequestAttributes();
            }
        }));
        assertThat(executionId.get()).as("a execução foi criada (status %s)", resp.getStatus())
                .isNotNull().isNotEqualTo("null");
        return executionId.get();
    }

    private MvcResult assinar(String path, String tenantDoToken) throws Exception {
        return mvc.perform(get(path).header("Authorization", "Bearer " + token(tenantDoToken)))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    private static String aguardarConteudo(MvcResult stream, String trecho) throws Exception {
        long limite = System.currentTimeMillis() + 10_000;
        String corpo = stream.getResponse().getContentAsString();
        while (!corpo.contains(trecho) && System.currentTimeMillis() < limite) {
            Thread.sleep(20);
            corpo = stream.getResponse().getContentAsString();
        }
        return corpo;
    }

    @Test
    @DisplayName("assinar a execução pelo id recebe os eventos de passo e o fim do fluxo, com o tenant real")
    void execucaoChegaAoCanalDoTenant() throws Exception {
        String executionId = executarComo("tenant-a");

        MvcResult stream = assinar("/api/stream/" + executionId, "tenant-a");
        liberar.countDown();

        String corpo = aguardarConteudo(stream, "\"flow_completed\"");
        assertThat(corpo).contains("\"connected\"", "\"step_completed\"", "\"step_started\"",
                "\"flow_completed\"");
        assertThat(corpo).as("o evento de fim leva o tenant da requisição, não SYSTEM")
                .contains("\"tenantId\":\"tenant-a\"")
                .doesNotContain("SYSTEM");
    }

    /**
     * A mesma execução vista de outro tenant: a rota com o tenant do dono responde 403, e a rota sem
     * tenant abre o canal do próprio tenant de quem assina — onde os eventos dela não chegam.
     */
    @Test
    @DisplayName("outro tenant: 403 no canal do dono e nada no próprio canal")
    void outroTenantNaoRecebe() throws Exception {
        String executionId = executarComo("tenant-a");

        mvc.perform(get("/api/stream/tenant-a/" + executionId)
                        .header("Authorization", "Bearer " + token("tenant-b")))
                .andExpect(status().isForbidden());
        MvcResult doOutro = assinar("/api/stream/" + executionId, "tenant-b");
        MvcResult doDono = assinar("/api/stream/" + executionId, "tenant-a");
        liberar.countDown();

        assertThat(aguardarConteudo(doDono, "\"flow_completed\"")).contains("\"flow_completed\"");
        String corpoDoOutro = doOutro.getResponse().getContentAsString();
        assertThat(corpoDoOutro).contains("\"connected\"").doesNotContain("step_", "flow_");
    }
}
