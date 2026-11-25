package br.com.archflow.api.flow;

import br.com.archflow.agent.persistence.InMemoryFlowRepository;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.api.agui.AgUiController;
import br.com.archflow.api.agui.DefaultAgUiEventMapper;
import br.com.archflow.api.agui.RunAgentInput;
import br.com.archflow.api.approval.dto.ApprovalResponse;
import br.com.archflow.api.approval.impl.ApprovalControllerImpl;
import br.com.archflow.api.approval.impl.ApprovalQueueService;
import br.com.archflow.api.audit.AuditTrail;
import br.com.archflow.api.config.ImpersonationFilter;
import br.com.archflow.api.mcp.server.SpringMcpServerController;
import br.com.archflow.api.mcp.server.WorkflowMcpServer;
import br.com.archflow.api.web.approval.SpringApprovalController;
import br.com.archflow.api.web.workflow.InMemoryWorkflowRuntimeStore;
import br.com.archflow.model.tenant.DefaultTenant;
import br.com.archflow.api.web.workflow.SpringWorkflowCrudController;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowMetadata;
import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.flow.FlowStatus;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.model.flow.StepType;
import br.com.archflow.observability.audit.InMemoryAuditRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RCHFLW-13: as três portas de execução (REST {@code /execute}, Run do AG-UI e {@code tools/call} do
 * MCP) criam a execução com o tenant da requisição — e a aprovação que ela abre aparece na fila
 * desse tenant.
 *
 * <p>Antes, as três montavam o contexto com tenant nulo, o motor gravava "SYSTEM", e a fila — filtrada
 * pelo tenant da requisição — nunca encontrava a pendência. O teste segue o caminho inteiro: header
 * {@code X-Tenant-Id} → {@link ImpersonationFilter} → {@code TenantContext} → contexto → estado
 * {@code AWAITING_APPROVAL} → {@link ApprovalQueueService} / endpoint da fila. Afirmar só o contexto
 * capturado não provaria que o tenant chega à fila.
 */
@DisplayName("Execução com o tenant da requisição — a aprovação cai na fila do tenant")
class TenantDaRequisicaoNaExecucaoTest {

    private static final String WORKFLOW_ID = "wf-gate";

    private FlowRepository flowRepository;
    private InMemoryStateManager stateManager;
    private FlowEngine engine;
    private ApprovalQueueService queue;
    private InMemoryWorkflowRuntimeStore store;
    private WorkflowDeserializer deserializer;

    @BeforeEach
    void setUp() {
        flowRepository = new InMemoryFlowRepository();
        stateManager = new InMemoryStateManager();
        engine = FlowEngineFactory.create(flowRepository, null, null, stateManager);
        queue = new ApprovalQueueService(stateManager, engine);
        store = new InMemoryWorkflowRuntimeStore();
        // Cada execução vira gate de aprovação → liberar. O id do fluxo é o da execução, como no
        // deserializer real.
        deserializer = json -> {
            String flowId = String.valueOf(json.get("id"));
            Flow flow = new SimpleFlow(flowId,
                    new FlowMetadata("Fluxo com gate", "", "1.0.0", null, null, List.of()),
                    List.of(new HumanApprovalStep("gate", List.of(edge("gate", "liberar")),
                                    Map.of("proposal", Map.of("pedido", 42),
                                            "description", "Liberar o pedido"), () -> engine),
                            passo("liberar")));
            flowRepository.save(flow);
            return flow;
        };
    }

    private static FlowStep passo(String id) {
        return new FlowStep() {
            @Override public String getId() { return id; }
            @Override public StepType getType() { return StepType.TOOL; }
            @Override public List<StepConnection> getConnections() { return List.of(); }
            @Override public CompletableFuture<StepResult> execute(ExecutionContext c) {
                return CompletableFuture.completedFuture(SimpleStepResult.ok(id, "liberado", 1));
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

    @AfterEach
    void limpa() {
        RequestContextHolder.resetRequestAttributes();
    }

    // ── portas de entrada ───────────────────────────────────────────────────

    /**
     * O fluxo com gate, no tenant de quem vai executá-lo: workflows agora são do tenant (RCHFLW-73), e
     * o id é único no store, então cada tenant tem o seu.
     */
    private String fluxoDo(String xTenantId) {
        String tenant = DefaultTenant.orDefault(xTenantId);
        String id = WORKFLOW_ID + "-" + tenant;
        if (!store.hasWorkflow(tenant, id)) {
            store.putWorkflow(tenant, id, new HashMap<>(Map.of(
                    "id", id, "metadata", Map.of("name", "Fluxo com gate"))));
        }
        return id;
    }

    private String execucaoUnica(String xTenantId, String workflowId) {
        return String.valueOf(store.executions(DefaultTenant.orDefault(xTenantId), workflowId, null)
                .get(0).get("id"));
    }

    private String executarPorRest(String xTenantId, Map<String, Object> input) throws Exception {
        SpringWorkflowCrudController controller = new SpringWorkflowCrudController(
                store, deserializer, engine, flowRepository,
                new AuditTrail(InMemoryAuditRepository::new), null);
        String workflowId = fluxoDo(xTenantId);
        return naRequisicao(xTenantId, () -> String.valueOf(
                controller.execute(workflowId, input).getBody().get("executionId")));
    }

    private String executarPeloAgUi(String xTenantId) throws Exception {
        AgUiController controller = new AgUiController(store, deserializer, engine,
                new EventStreamRegistry(), new DefaultAgUiEventMapper(), stateManager, new ObjectMapper());
        String workflowId = fluxoDo(xTenantId);
        naRequisicao(xTenantId, () -> controller.run(workflowId,
                new RunAgentInput(List.of(), Map.of(), List.of(), List.of(), null, null)));
        // O AG-UI não devolve o id no corpo (é um stream); a execução criada é a única do fluxo.
        return execucaoUnica(xTenantId, workflowId);
    }

    private String executarPeloMcp(String xTenantId) throws Exception {
        SpringMcpServerController controller = new SpringMcpServerController(
                new WorkflowMcpServer(store, deserializer, engine, flowRepository, new ObjectMapper()));
        // handle() é chamado dentro da requisição; o despacho segue numa thread virtual, sem
        // requisição — é exatamente a travessia que o tenant tem de sobreviver.
        String workflowId = fluxoDo(xTenantId);
        var resposta = naRequisicao(xTenantId, () -> controller.handle(Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", Map.of("name", "workflow_" + workflowId, "arguments", Map.of()))));
        var corpo = resposta.get(30, TimeUnit.SECONDS).getBody();
        assertThat(corpo.isError()).as(String.valueOf(corpo)).isFalse();
        return execucaoUnica(xTenantId, workflowId);
    }

    /** Roda {@code acao} como um controller rodaria: depois do filtro de tenant, com a requisição no contexto. */
    private <T> T naRequisicao(String xTenantId, Supplier<T> acao) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/workflows");
        if (xTenantId != null) {
            req.addHeader(ImpersonationFilter.HEADER_TENANT, xTenantId);
        }
        AtomicReference<T> resultado = new AtomicReference<>();
        new ImpersonationFilter(false).doFilter(req, new MockHttpServletResponse(), (rq, rs) -> {
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
            try {
                resultado.set(acao.get());
            } finally {
                RequestContextHolder.resetRequestAttributes();
            }
        });
        return resultado.get();
    }

    /**
     * Espera a travessia terminar (o registro de execução deixa RUNNING — as três portas o fecham no
     * fim do run, PAUSED quando suspensa) e devolve o estado durável. Olhar o status no meio da
     * travessia pegaria o estado antes de o motor terminar de gravá-lo.
     */
    private FlowState aguardandoAprovacao(String executionId) throws InterruptedException {
        long limite = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < limite) {
            Object status = execucao(executionId).get("status");
            if (!"RUNNING".equals(status)) {
                FlowState state = stateManager.loadState(executionId);
                assertThat(state.getStatus()).as("status do estado de " + executionId)
                        .isEqualTo(FlowStatus.AWAITING_APPROVAL);
                return state;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("a execução " + executionId + " não terminou a travessia");
    }

    /** A execução, em qualquer dos tenants que os cenários usam (o teste não depende de qual). */
    private Map<String, Object> execucao(String executionId) {
        for (String tenant : List.of("acme", "globex", DefaultTenant.ID)) {
            Map<String, Object> execution = store.getExecution(tenant, executionId);
            if (execution != null) {
                return execution;
            }
        }
        throw new AssertionError("execução " + executionId + " não encontrada");
    }

    private static List<String> fluxos(List<ApprovalResponse> pendentes) {
        return pendentes.stream().map(ApprovalResponse::flowId).toList();
    }

    private void aprovacaoNaFilaDoTenant(String executionId, String tenant) throws Exception {
        assertThat(aguardandoAprovacao(executionId).getTenantId()).isEqualTo(tenant);
        assertThat(fluxos(queue.listPending(tenant))).containsExactly(executionId);
        assertThat(fluxos(queue.listPending("outro-tenant"))).isEmpty();
    }

    // ── cenários ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("REST /execute com X-Tenant-Id acme → aprovação na fila de acme")
    void restUsaOTenantDaRequisicao() throws Exception {
        aprovacaoNaFilaDoTenant(executarPorRest("acme", null), "acme");
    }

    @Test
    @DisplayName("Run do AG-UI com X-Tenant-Id acme → aprovação na fila de acme")
    void agUiUsaOTenantDaRequisicao() throws Exception {
        aprovacaoNaFilaDoTenant(executarPeloAgUi("acme"), "acme");
    }

    @Test
    @DisplayName("tools/call do MCP com X-Tenant-Id acme → o tenant atravessa a thread virtual")
    void mcpLevaOTenantParaAThreadVirtual() throws Exception {
        aprovacaoNaFilaDoTenant(executarPeloMcp("acme"), "acme");
    }

    @Test
    @DisplayName("sem tenant na requisição → tenant padrão __default__, nunca SYSTEM")
    void semTenantCaiNoPadraoCanonico() throws Exception {
        aprovacaoNaFilaDoTenant(executarPorRest(null, null), "__default__");
        assertThat(fluxos(queue.listPending("SYSTEM"))).isEmpty();
    }

    @Test
    @DisplayName("um \"tenantId\" no corpo não escolhe o tenant — continua o da requisição")
    void corpoNaoEscolheOTenant() throws Exception {
        String executionId = executarPorRest("acme", Map.of("tenantId", "evil"));

        aprovacaoNaFilaDoTenant(executionId, "acme");
        assertThat(fluxos(queue.listPending("evil"))).isEmpty();
    }

    @Test
    @DisplayName("a fila sem parâmetro (como a UI de quem não é superadmin) lista só o tenant da requisição")
    void endpointSemParametroUsaOTenantDaRequisicao() throws Exception {
        String daAcme = executarPorRest("acme", null);
        aguardandoAprovacao(daAcme);
        String daGlobex = executarPorRest("globex", null);
        aguardandoAprovacao(daGlobex);

        SpringApprovalController endpoint = new SpringApprovalController(new ApprovalControllerImpl(queue));

        List<ApprovalResponse> vistoPelaAcme = naRequisicao("acme", () -> endpoint.listPending(null));
        Map<String, Integer> contagemDaAcme = naRequisicao("acme", () -> endpoint.pendingCount(null));

        assertThat(fluxos(vistoPelaAcme)).containsExactly(daAcme);
        assertThat(contagemDaAcme).containsEntry("count", 1);
    }
}
