package br.com.archflow.api.flow;

import br.com.archflow.agent.persistence.InMemoryFlowRepository;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.api.agent.mcp.ContadorDeUso;
import br.com.archflow.api.agent.mcp.McpAgentRunner;
import br.com.archflow.api.agent.mcp.TabelaDePrecos;
import br.com.archflow.api.agent.mcp.ToolAccessPolicy;
import br.com.archflow.api.agent.mcp.ToolApprovalPolicy;
import br.com.archflow.api.agent.mcp.ToolTrustPolicy;
import br.com.archflow.api.agui.AgUiAgentController;
import br.com.archflow.api.agui.AgUiController;
import br.com.archflow.api.agui.DefaultAgUiEventMapper;
import br.com.archflow.api.agui.RunAgentInput;
import br.com.archflow.api.audit.AuditTrail;
import br.com.archflow.api.config.ImpersonationFilter;
import br.com.archflow.api.usage.InMemoryRepositorioDeUso;
import br.com.archflow.api.usage.LancamentoDeUso;
import br.com.archflow.api.usage.RegistroDeUso;
import br.com.archflow.api.usage.RegistroDeUsoListener;
import br.com.archflow.api.usage.RepositorioDeUso;
import br.com.archflow.api.usage.UsoSomado;
import br.com.archflow.api.web.workflow.InMemoryWorkflowRuntimeStore;
import br.com.archflow.api.web.workflow.SpringExecutionController;
import br.com.archflow.api.web.workflow.SpringWorkflowCrudController;
import br.com.archflow.decision.DecisionUsageListener;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.lifecycle.FlowLifecycleListeners;
import br.com.archflow.langchain4j.mcp.McpClient;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.langchain4j.provider.LLMResolutionRequest;
import br.com.archflow.model.config.ResolvedLLMConfig;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowMetadata;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.model.flow.StepType;
import br.com.archflow.observability.audit.InMemoryAuditRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RCHFLW-75: todo caminho que chama um LLM conta tokens e custo no mesmo {@link ContadorDeUso}, e o
 * que foi contado fica no {@link RegistroDeUso} do tenant — de onde saem o detalhe da execução e as
 * telas de uso.
 *
 * <p>Antes, só o VendaX e o invoke criavam contador: pela lista ({@code /execute}) e pelo editor
 * (Run do AG-UI) o nó de agente recebia {@code uso = null} e o laço pulava a contagem; o chat do
 * AG-UI não contava nada; e o que o VendaX contava saía no resultado e mais nada.
 *
 * <p>O passo {@link #passoQueChamaOModelo} faz o que o nó de agente e o de decisão fazem: pega o
 * contador do contexto ({@code ContadorDeUso.de}) e soma o turno — sem contador, não há onde somar.
 */
@DisplayName("Uso de modelo medido em todos os caminhos de execução")
class UsoMedidoEmTodosOsCaminhosTest {

    private static final String TENANT = "acme";

    private RepositorioDeUso repositorio;
    private RegistroDeUso registro;
    private RegistroDeUsoListener listener;
    private InMemoryFlowRepository flowRepository;
    private InMemoryStateManager stateManager;
    private FlowEngine engine;
    private InMemoryWorkflowRuntimeStore store;
    private WorkflowDeserializer deserializer;

    @BeforeEach
    void setUp() {
        repositorio = new InMemoryRepositorioDeUso();
        // 100 centavos por milhão de tokens de entrada, 200 por milhão de saída.
        registro = new RegistroDeUso(repositorio, TabelaDePrecos.doTexto("openrouter/m=100,200").tabela());
        listener = new RegistroDeUsoListener(registro);
        // Antes do create: a fábrica fotografa os listeners do processo — como no bean flowEngine.
        FlowLifecycleListeners.register(listener);
        flowRepository = new InMemoryFlowRepository();
        stateManager = new InMemoryStateManager();
        engine = FlowEngineFactory.create(flowRepository, null, null, stateManager);
        store = new InMemoryWorkflowRuntimeStore();
        deserializer = json -> {
            Flow flow = new SimpleFlow(String.valueOf(json.get("id")),
                    new FlowMetadata("Fluxo com modelo", "", "1.0.0", null, null, List.of()),
                    List.of(passoQueChamaOModelo()));
            flowRepository.save(flow);
            return flow;
        };
    }

    @AfterEach
    void tearDown() {
        FlowLifecycleListeners.unregister(listener);
        RequestContextHolder.resetRequestAttributes();
    }

    /** Um turno de agente (1.000 + 200 tokens) e uma decisão (300 + 50), pelo contexto, como os nós. */
    private static FlowStep passoQueChamaOModelo() {
        return new FlowStep() {
            @Override public String getId() { return "agente"; }
            @Override public StepType getType() { return StepType.TOOL; }
            @Override public List<StepConnection> getConnections() { return List.of(); }
            @Override public CompletableFuture<StepResult> execute(ExecutionContext c) {
                ContadorDeUso.de(c).ifPresent(uso -> uso.somar("openrouter", "openrouter/m", 1_000, 200));
                DecisionUsageListener.from(c).ifPresent(l -> l.onDecision("openrouter", "m", 300L, 50L, 0.002));
                return CompletableFuture.completedFuture(SimpleStepResult.ok("agente", "ok", 1));
            }
        };
    }

    // ── fluxos pelo motor ───────────────────────────────────────────────────

    @Test
    @DisplayName("lista/REST: /execute conta o uso da execução e o detalhe mostra tokens e custo")
    void restExecuteCountsUsageAndDetailShowsIt() throws Exception {
        var controller = new SpringWorkflowCrudController(store, deserializer, engine, flowRepository,
                new AuditTrail(InMemoryAuditRepository::new), null);
        String workflowId = fluxo();

        String executionId = naRequisicao(() -> String.valueOf(
                controller.execute(workflowId, Map.of("input", "oi")).getBody().get("executionId")));
        aguardarFim(executionId);

        UsoSomado uso = registro.daExecucao(TENANT, executionId).orElseThrow();
        assertThat(uso.tokensEntrada()).isEqualTo(1_300);
        assertThat(uso.tokensSaida()).isEqualTo(250);
        assertThat(uso.custoDeclaradoUsd()).isNull();
        assertThat(lancamentos()).singleElement().satisfies(l -> {
            assertThat(l.tenantId()).isEqualTo(TENANT);
            assertThat(l.origem()).isEqualTo(LancamentoDeUso.ORIGEM_FLUXO);
            // Dois modelos no mesmo contador ("openrouter/m" e "openrouter/m" da decisão é o mesmo id):
            // um só modelo, então há preço — 1.300×100 + 250×200 = 180.000 centavos×milhão → 1 centavo.
            assertThat(l.custoCentavos()).isEqualTo(1L);
        });

        var detalhe = new SpringExecutionController(store, engine, stateManager, null);
        detalhe.setRegistroDeUso(registro);
        @SuppressWarnings("unchecked")
        Map<String, Object> usage = (Map<String, Object>) naRequisicao(() -> detalhe.get(executionId).getBody())
                .get("usage");
        assertThat(usage).containsEntry("inputTokens", 1_300L).containsEntry("outputTokens", 250L)
                .containsEntry("costCents", 1L).containsEntry("withoutPrice", 0)
                // O modelo que rodou (RCHFLW-190): antes o detalhe não dizia qual.
                .containsEntry("models", List.of("openrouter/m"));
    }

    @Test
    @DisplayName("editor (Run do AG-UI): o fluxo executado pelo editor também conta, no tenant da requisição")
    void agUiRunCountsUsage() throws Exception {
        var controller = new AgUiController(store, deserializer, engine, new EventStreamRegistry(),
                new DefaultAgUiEventMapper(), stateManager, flowRepository, new ObjectMapper());
        String workflowId = fluxo();

        naRequisicao(() -> controller.run(workflowId,
                new RunAgentInput(List.of(), Map.of(), List.of(), List.of(), null, null)));
        String executionId = String.valueOf(store.executions(TENANT, workflowId, null).get(0).get("id"));
        aguardarFim(executionId);

        assertThat(registro.daExecucao(TENANT, executionId)).get()
                .extracting(UsoSomado::tokens).isEqualTo(1_550L);
        assertThat(registro.doTenantNoMes(TENANT, registro.mesAtual()).execucoes()).isEqualTo(1);
        assertThat(registro.doTenantNoMes("outro", registro.mesAtual())).isEqualTo(UsoSomado.VAZIO);
    }

    @Test
    @DisplayName("VendaX com fluxo: o contador de quem chamou é mantido e contado uma vez só")
    void preInjectedCounterIsKeptAndCountedOnce() throws Exception {
        String flowId = "vendax-exec-1";
        Flow flow = deserializer.toFlow(new HashMap<>(Map.of("id", flowId)));
        ExecutionContext ctx = new DefaultExecutionContext(TENANT, "runner", flowId,
                MessageWindowChatMemory.builder().maxMessages(20).build());
        ContadorDeUso doDispatcher = new ContadorDeUso();
        ContadorDeUso.injetar(ctx, doDispatcher);

        engine.execute(flow, ctx).get(10, TimeUnit.SECONDS);
        // O dispatcher relata o mesmo contador depois (o laço relata no fim de cada volta).
        registro.registrar(TENANT, null, LancamentoDeUso.ORIGEM_AGENTE, doDispatcher);

        assertThat(lancamentos()).singleElement().satisfies(l -> {
            assertThat(l.id()).isEqualTo(doDispatcher.execucaoId());
            assertThat(l.execucaoDeFluxoId()).isEqualTo(flowId);
            assertThat(l.tokensEntrada()).isEqualTo(1_300L);
        });
    }

    @Test
    @DisplayName("nó de chat (llm-chat): o adapter relata pela ChatTurn ao mesmo contador da execução")
    void chatNodeThroughAdapterCountsUsage() throws Exception {
        ChatModel modelo = new ChatModel() {
            @Override public ChatResponse chat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from("resposta"))
                        .tokenUsage(new TokenUsage(900, 100)).build();
            }
        };
        // O que os adapters de chat fazem no execute: ChatTurn com o contexto, o provedor e o modelo.
        FlowStep chat = new FlowStep() {
            @Override public String getId() { return "chat"; }
            @Override public StepType getType() { return StepType.TOOL; }
            @Override public List<StepConnection> getConnections() { return List.of(); }
            @Override public CompletableFuture<StepResult> execute(ExecutionContext c) {
                String texto = br.com.archflow.langchain4j.core.chat.ChatTurn.generate(modelo, null, "oi", c,
                        "openrouter", "m");
                return CompletableFuture.completedFuture(SimpleStepResult.ok("chat", texto, 1));
            }
        };
        deserializer = json -> {
            Flow flow = new SimpleFlow(String.valueOf(json.get("id")),
                    new FlowMetadata("Fluxo de chat", "", "1.0.0", null, null, List.of()), List.of(chat));
            flowRepository.save(flow);
            return flow;
        };
        var controller = new SpringWorkflowCrudController(store, deserializer, engine, flowRepository,
                new AuditTrail(InMemoryAuditRepository::new), null);
        String workflowId = fluxo();

        String executionId = naRequisicao(() -> String.valueOf(
                controller.execute(workflowId, Map.of("input", "oi")).getBody().get("executionId")));
        aguardarFim(executionId);

        assertThat(lancamentos()).singleElement().satisfies(l -> {
            assertThat(l.execucaoDeFluxoId()).isEqualTo(executionId);
            assertThat(l.modelo()).isEqualTo("openrouter/m");
            assertThat(l.tokensEntrada()).isEqualTo(900L);
            assertThat(l.tokensSaida()).isEqualTo(100L);
            assertThat(l.custoCentavos()).isEqualTo(1L);
        });
    }

    // ── laço de agente fora de fluxo (invoke, VendaX por nome, playground) ──

    @Test
    @DisplayName("invoke/VendaX por nome: o laço sem contador ganha um e registra no tenant da execução")
    void agentLoopWithoutCounterIsCounted() {
        ChatModel modelo = new ChatModel() {
            @Override public ChatResponse chat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from("pronto"))
                        .tokenUsage(new TokenUsage(400, 80)).build();
            }
        };
        McpAgentRunner runner = new McpAgentRunner(resolvedor(modelo, null), configDaPlataforma());
        runner.setRegistroDeUso(registro);
        McpClient client = mock(McpClient.class);
        when(client.listTools()).thenReturn(CompletableFuture.completedFuture(List.of()));

        runner.run(TENANT, "sistema", "oi", client, new McpAgentRunner.Options(ToolAccessPolicy.allowAll(),
                ToolTrustPolicy.untrustedByDefault(), ToolApprovalPolicy.none(), 4));

        assertThat(lancamentos()).singleElement().satisfies(l -> {
            assertThat(l.tenantId()).isEqualTo(TENANT);
            assertThat(l.origem()).isEqualTo(LancamentoDeUso.ORIGEM_AGENTE);
            assertThat(l.execucaoDeFluxoId()).isNull();
            assertThat(l.modelo()).isEqualTo("openrouter/m");
            assertThat(l.tokensEntrada()).isEqualTo(400L);
            assertThat(l.tokensSaida()).isEqualTo(80L);
            assertThat(l.custoCentavos()).isEqualTo(1L);
        });
    }

    // ── chat de agente do AG-UI ─────────────────────────────────────────────

    @Test
    @DisplayName("AG-UI (chat de agente): os tokens da resposta são registrados no tenant da requisição")
    void agUiAgentChatIsCounted() throws Exception {
        StreamingChatModel streaming = new StreamingChatModel() {
            @Override public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("olá"))
                        .tokenUsage(new TokenUsage(70, 30)).build());
            }
        };
        var controller = new AgUiAgentController(resolvedor(null, streaming), configDaPlataforma(), new ObjectMapper());
        controller.setRegistroDeUso(registro);

        naRequisicao(() -> controller.agent(new RunAgentInput(List.of(Map.of("role", "user", "content", "oi")),
                null, null, null, "t1", "r1")));

        assertThat(lancamentos()).singleElement().satisfies(l -> {
            assertThat(l.tenantId()).isEqualTo(TENANT);
            assertThat(l.origem()).isEqualTo(LancamentoDeUso.ORIGEM_AG_UI);
            assertThat(l.tokensEntrada()).isEqualTo(70L);
            assertThat(l.tokensSaida()).isEqualTo(30L);
        });
    }

    // ── apoio ───────────────────────────────────────────────────────────────

    private List<LancamentoDeUso> lancamentos() {
        YearMonth mes = registro.mesAtual();
        return repositorio.doPeriodo(mes.atDay(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant(),
                mes.plusMonths(1).atDay(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());
    }

    private String fluxo() {
        String id = "wf-modelo";
        store.putWorkflow(TENANT, id, new HashMap<>(Map.of("id", id, "metadata", Map.of("name", "Fluxo"))));
        return id;
    }

    private void aguardarFim(String executionId) throws InterruptedException {
        long limite = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < limite) {
            Object status = store.getExecution(TENANT, executionId).get("status");
            if (!"RUNNING".equals(status)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("a execução " + executionId + " não terminou");
    }

    private static ResolvedLLMConfig configDaPlataforma() {
        return ResolvedLLMConfig.builder().provider("openrouter").model("m").build();
    }

    private static LLMConfigResolver resolvedor(ChatModel modelo, StreamingChatModel streaming) {
        return new LLMConfigResolver() {
            @Override public ResolvedLLMConfig resolve(LLMResolutionRequest request) {
                return configDaPlataforma();
            }
            @Override public ChatModel resolveModel(LLMResolutionRequest request) {
                return modelo;
            }
            @Override public StreamingChatModel resolveStreamingModel(LLMResolutionRequest request) {
                return streaming;
            }
        };
    }

    /** Como um controller roda: depois do filtro de tenant, com a requisição no contexto. */
    private <T> T naRequisicao(Supplier<T> acao) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/workflows");
        req.addHeader(ImpersonationFilter.HEADER_TENANT, TENANT);
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
}
