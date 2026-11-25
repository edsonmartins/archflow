package br.com.archflow.api.flow;

import br.com.archflow.agent.persistence.InMemoryFlowRepository;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.api.agent.mcp.McpAgentComponent;
import br.com.archflow.api.agent.mcp.McpAgentHost;
import br.com.archflow.api.agent.mcp.McpAgentRunner;
import br.com.archflow.api.agui.AgUiController;
import br.com.archflow.api.agui.DefaultAgUiEventMapper;
import br.com.archflow.api.agui.RunAgentInput;
import br.com.archflow.api.approval.dto.ApprovalResponse;
import br.com.archflow.api.approval.dto.ApprovalSubmitRequest;
import br.com.archflow.api.approval.impl.ApprovalQueueService;
import br.com.archflow.api.audit.AuditTrail;
import br.com.archflow.api.mcp.server.WorkflowMcpServer;
import br.com.archflow.api.web.workflow.InMemoryWorkflowRuntimeStore;
import br.com.archflow.api.web.workflow.SpringWorkflowCrudController;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.langchain4j.mcp.McpClient;
import br.com.archflow.langchain4j.mcp.McpModel;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowMetadata;
import br.com.archflow.model.flow.FlowStatus;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.model.flow.StepType;
import br.com.archflow.model.tenant.DefaultTenant;
import br.com.archflow.observability.audit.InMemoryAuditRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * RCHFLW-48: o nó {@code mcp-agent} roda em todo ponto de entrada — Executar do editor (AG-UI),
 * fluxo como tool MCP e a retomada depois de aprovar na fila —, não só pelo {@code POST /execute}.
 *
 * <p>Antes, os três montavam o contexto sem o {@link McpAgentHost} (a chave é {@code transient}, não
 * volta do checkpoint), e o passo falhava com "nenhum McpAgentHost no contexto". No caso da
 * aprovação a falha só aparecia depois que o humano tinha aprovado.</p>
 *
 * <p>Peças reais: controllers, servidor MCP, fila de aprovações, motor montado pelo
 * {@link FlowEngineFactory} com o gancho de retomada (como o bean do servidor) e o
 * {@link McpAgentComponent} de verdade. Só o laço LLM é trocado por um runner que responde direto — o
 * que se prova aqui é que o host chega ao passo, não o laço.</p>
 */
@DisplayName("McpAgentHost em todo ponto de entrada — o nó mcp-agent executa")
class McpAgentHostEmTodoPontoDeEntradaTest {

    private FlowRepository flowRepository;
    private InMemoryStateManager stateManager;
    private FlowEngine engine;
    private InMemoryWorkflowRuntimeStore store;
    private HostFalso host;
    /** {@code true}: portão de aprovação antes do agente. */
    private boolean comPortao;

    /** Host de teste: conta as execuções do laço. */
    private static final class HostFalso implements McpAgentHost {
        final AtomicInteger execucoes = new AtomicInteger();
        final List<String> tenants = new ArrayList<>();
        private final McpAgentRunner runner = new McpAgentRunner(null, null) {
            @Override
            public Result run(String tenantId, String systemPrompt, String userMessage,
                              McpClient client, Options opts) {
                execucoes.incrementAndGet();
                tenants.add(tenantId);
                return new Result("resposta do agente", List.of());
            }
        };

        @Override public McpAgentRunner runner() { return runner; }
        @Override public McpClient clientFor(String tenantId, String serverRef) { return mock(McpClient.class); }
    }

    @BeforeEach
    void setUp() {
        flowRepository = new InMemoryFlowRepository();
        stateManager = new InMemoryStateManager();
        host = new HostFalso();
        // O mesmo gancho que o bean flowEngine configura (ArchflowBeanConfiguration).
        engine = FlowEngineFactory.create(flowRepository, null, null, stateManager, null, null,
                16, 3_600_000L, 8, false, ctx -> McpAgentHost.inject(ctx, host));
        store = new InMemoryWorkflowRuntimeStore();
        comPortao = false;
    }

    private Flow fluxo(String flowId) {
        McpAgentComponent agente = new McpAgentComponent();
        agente.initialize(Map.of("systemPrompt", "você é um agente"));
        FlowStep passoDoAgente = new ComponentStep("agente", StepType.AGENT, McpAgentComponent.COMPONENT_ID,
                "execute", List.of(), null, null, agente);
        List<FlowStep> passos = comPortao
                ? List.of(new HumanApprovalStep("gate", List.of(edge("gate", "agente")),
                        Map.of("description", "Liberar o agente"), () -> engine), passoDoAgente)
                : List.of(passoDoAgente);
        return new SimpleFlow(flowId, new FlowMetadata("Com MCP Agent", "", "1.0.0", null, null, List.of()),
                passos);
    }

    private final WorkflowDeserializer deserializer = json -> fluxo(String.valueOf(json.get("id")));

    private static StepConnection edge(String from, String to) {
        return new StepConnection() {
            @Override public String getSourceId() { return from; }
            @Override public String getTargetId() { return to; }
            @Override public Optional<String> getCondition() { return Optional.empty(); }
            @Override public boolean isErrorPath() { return false; }
        };
    }

    private String novoWorkflow() {
        String id = "wf-mcp-" + System.nanoTime();
        store.putWorkflow(DefaultTenant.ID, id, new HashMap<>(Map.of(
                "id", id, "metadata", Map.of("name", "Com MCP Agent"))));
        return id;
    }

    private String unicaExecucao(String workflowId) {
        return String.valueOf(store.executions(DefaultTenant.ID, workflowId, 1).get(0).get("id"));
    }

    private Map<String, Object> aguardarRegistro(String executionId, String status) throws Exception {
        long limite = System.currentTimeMillis() + 10_000;
        Map<String, Object> registro = store.getExecution(DefaultTenant.ID, executionId);
        while (!status.equals(String.valueOf(registro.get("status"))) && System.currentTimeMillis() < limite) {
            Thread.sleep(20);
            registro = store.getExecution(DefaultTenant.ID, executionId);
        }
        assertThat(registro.get("status"))
                .as("status de %s (erro: %s)", executionId, registro.get("error"))
                .isEqualTo(status);
        return registro;
    }

    @Test
    @DisplayName("Executar do editor (AG-UI): o mcp-agent roda e a execução termina COMPLETED")
    void agUi() throws Exception {
        AgUiController agUi = new AgUiController(store, deserializer, engine, new EventStreamRegistry(),
                new DefaultAgUiEventMapper(), stateManager, flowRepository, new ObjectMapper());
        agUi.setMcpAgentHost(host);
        String workflowId = novoWorkflow();

        agUi.run(workflowId, new RunAgentInput(
                List.of(Map.of("role", "user", "content", "oi")), null, null, null, null, null));

        aguardarRegistro(unicaExecucao(workflowId), "COMPLETED");
        assertThat(host.execucoes).hasValue(1);
    }

    @Test
    @DisplayName("fluxo como tool MCP: o mcp-agent roda e o tools/call devolve a saída")
    void toolMcp() throws Exception {
        WorkflowMcpServer mcp = new WorkflowMcpServer(store, deserializer, engine, flowRepository,
                new ObjectMapper(), host);
        String workflowId = novoWorkflow();

        McpModel.ToolResult resultado = mcp.callTool(new McpModel.ToolArguments(
                "workflow_" + workflowId, Map.of("input", "oi"))).get(10, TimeUnit.SECONDS);

        assertThat(resultado.isError()).as(String.valueOf(resultado)).isFalse();
        assertThat(String.valueOf(resultado)).contains("resposta do agente");
        assertThat(host.execucoes).hasValue(1);
    }

    /**
     * A retomada pela fila usa um contexto novo ({@code createResumeContext}): a suspensão
     * cooperativa já tirou o fluxo das execuções vivas, e o host injetado pelo {@code /execute} ficou
     * no contexto original. É o gancho do motor que o devolve.
     */
    @Test
    @DisplayName("aprovar na fila: a retomada roda o mcp-agent depois do portão")
    void retomadaDepoisDaAprovacao() throws Exception {
        comPortao = true;
        // O /execute injeta o próprio host; um outro objeto aqui mostra que quem atende a retomada
        // é o gancho do motor, não o contexto original.
        SpringWorkflowCrudController crud = new SpringWorkflowCrudController(store, deserializer, engine,
                flowRepository, new AuditTrail(InMemoryAuditRepository::new), mock(McpAgentHost.class));
        ApprovalQueueService fila = new ApprovalQueueService(stateManager, engine);
        String workflowId = novoWorkflow();

        String executionId = String.valueOf(crud.execute(workflowId, Map.of("input", "oi"))
                .getBody().get("executionId"));
        aguardarRegistro(executionId, "PAUSED");
        assertThat(host.execucoes).hasValue(0);

        String requestId = fila.listPending("all").stream()
                .filter(p -> executionId.equals(p.flowId()))
                .map(ApprovalResponse::requestId)
                .findFirst().orElseThrow();
        fila.submitDecision(requestId, new ApprovalSubmitRequest("all", "APPROVED", null, "diretor", "ok"));

        long limite = System.currentTimeMillis() + 10_000;
        while (stateManager.loadState(executionId).getStatus() != FlowStatus.COMPLETED
                && System.currentTimeMillis() < limite) {
            Thread.sleep(20);
        }
        assertThat(stateManager.loadState(executionId).getStatus())
                .as("erro: %s", stateManager.loadState(executionId).getError())
                .isEqualTo(FlowStatus.COMPLETED);
        assertThat(host.execucoes).hasValue(1);
        assertThat(host.tenants).containsExactly(DefaultTenant.ID);
    }
}
