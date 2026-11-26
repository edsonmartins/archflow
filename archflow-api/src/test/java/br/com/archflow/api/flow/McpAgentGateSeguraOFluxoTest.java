package br.com.archflow.api.flow;

import br.com.archflow.agent.persistence.InMemoryFlowRepository;
import br.com.archflow.api.agent.mcp.InMemoryMcpAgentStateStore;
import br.com.archflow.api.agent.mcp.McpAgentHost;
import br.com.archflow.api.agent.mcp.McpAgentRunner;
import br.com.archflow.api.agent.mcp.RetomadaDoLaco;
import br.com.archflow.api.approval.dto.ApprovalResponse;
import br.com.archflow.api.approval.dto.ApprovalSubmitRequest;
import br.com.archflow.api.approval.impl.ApprovalQueueService;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.langchain4j.mcp.McpClient;
import br.com.archflow.langchain4j.mcp.McpModel;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.langchain4j.provider.LLMResolutionRequest;
import br.com.archflow.model.config.ResolvedLLMConfig;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowMetadata;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.model.flow.FlowStatus;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.model.flow.StepType;
import br.com.archflow.plugin.api.catalog.DefaultComponentCatalog;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RCHFLW-185: um nó {@code mcp-agent} cuja tool exige aprovação segura o <b>fluxo</b>, não só o laço.
 *
 * <p>Antes, o laço suspendia antes da tool (certo), mas o passo devolvia a suspensão como saída comum: a
 * execução terminava COMPLETED, o nó seguinte rodava sobre um texto vazio, a aprovação ia para a fila
 * avulsa do laço (sem prazo, sem a execução) e, aprovada, a resposta não voltava a lugar nenhum — e quem
 * aprovou não ficava gravado.</p>
 *
 * <p>Peças reais: o passo como a fábrica do servidor o monta, o motor do {@link FlowEngineFactory} com o
 * gancho de retomada, o {@link McpAgentRunner} de verdade com store em memória e a fila de aprovações.
 * Só o modelo (roteiro: chama a tool, depois responde) e o servidor MCP são falsos.</p>
 */
@DisplayName("mcp-agent com tool sob aprovação segura o fluxo no portão do motor")
class McpAgentGateSeguraOFluxoTest {

    private static final String FLOW_ID = "exec-estorno";
    private static final String TENANT = "acme";
    private static final String TOOL = "estornar";

    private FlowRepository flowRepository;
    private InMemoryStateManager stateManager;
    private InMemoryMcpAgentStateStore lacos;
    private FlowEngine engine;
    private ApprovalQueueService fila;
    private RetomadaDoLaco retomada;
    private Host host;
    /** O motor que o passo vê; {@code null} = o mesmo da fila. Trocado para falhar o portão. */
    private volatile FlowEngine motorDoPasso;
    private final List<Object> entradasDoFinal = new CopyOnWriteArrayList<>();

    /** Servidor MCP falso com uma tool, que registra os argumentos de cada chamada. */
    private static final class Servidor implements McpClient {
        final List<Map<String, Object>> chamadas = new CopyOnWriteArrayList<>();

        @Override public void connect() { }
        @Override public CompletableFuture<McpModel.ServerInfo> initialize() {
            return CompletableFuture.completedFuture(null);
        }
        @Override public void initialized() { }
        @Override public boolean isConnected() { return true; }
        @Override public void close() { }
        @Override public McpModel.ServerMetadata getServerMetadata() {
            return new McpModel.ServerMetadata("fake");
        }
        @Override public McpModel.ServerCapabilities getServerCapabilities() {
            return McpModel.ServerCapabilities.toolsOnly();
        }
        @Override public CompletableFuture<List<McpModel.Tool>> listTools() {
            Map<String, Object> schema = Map.of("type", "object",
                    "properties", Map.of("pedido", Map.of("type", "string")));
            return CompletableFuture.completedFuture(List.of(new McpModel.Tool(TOOL, "estorna", schema)));
        }
        @Override public CompletableFuture<McpModel.ToolResult> callTool(McpModel.ToolArguments a) {
            chamadas.add(a.arguments());
            return CompletableFuture.completedFuture(McpModel.ToolResult.text("estornado"));
        }
    }

    /** Roteiro: o primeiro turno pede a tool; os seguintes respondem. */
    private static final class Modelo implements ChatModel {
        int turnos;
        /** Roda no primeiro turno, antes de o modelo pedir a tool — onde um cancelamento chega. */
        volatile Runnable noPrimeiroTurno;

        @Override public ChatResponse chat(ChatRequest request) {
            if (turnos == 0 && noPrimeiroTurno != null) {
                noPrimeiroTurno.run();
            }
            AiMessage resposta = turnos++ == 0
                    ? AiMessage.from(ToolExecutionRequest.builder()
                            .id("c-1").name(TOOL).arguments("{\"pedido\":\"PED-777\"}").build())
                    : AiMessage.from("Estorno do pedido feito");
            return ChatResponse.builder().aiMessage(resposta).tokenUsage(new TokenUsage(10, 5)).build();
        }
    }

    private final class Host implements McpAgentHost {
        final Servidor servidor = new Servidor();
        final Modelo modelo = new Modelo();
        /** Servidor MCP fora do ar para o tenant: o host não entrega cliente. */
        volatile boolean semCliente;
        private final McpAgentRunner runner;

        Host() {
            ResolvedLLMConfig config = ResolvedLLMConfig.builder().provider("openai").model("fake").build();
            LLMConfigResolver resolver = new LLMConfigResolver() {
                @Override public ResolvedLLMConfig resolve(LLMResolutionRequest r) { return config; }
                @Override public ChatModel resolveModel(LLMResolutionRequest r) { return modelo; }
            };
            this.runner = new McpAgentRunner(resolver, config, null, 0, lacos);
        }

        @Override public McpAgentRunner runner() { return runner; }
        @Override public McpClient clientFor(String tenantId, String serverRef) {
            if (semCliente) {
                throw new IllegalStateException("servidor MCP indisponível");
            }
            return servidor;
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        flowRepository = new InMemoryFlowRepository();
        stateManager = new InMemoryStateManager();
        lacos = new InMemoryMcpAgentStateStore();
        host = new Host();
        engine = FlowEngineFactory.create(flowRepository, null, null, stateManager, null, null,
                16, 3_600_000L, 8, false, ctx -> McpAgentHost.inject(ctx, host));
        retomada = new RetomadaDoLaco(lacos, host);
        fila = new ApprovalQueueService(stateManager, engine, Duration.ofMinutes(2),
                java.time.Clock.systemUTC(), retomada);
        motorDoPasso = null;
        entradasDoFinal.clear();
    }

    /** agente (mcp-agent, montado pela fábrica do servidor) → final (registra a entrada que recebeu). */
    @SuppressWarnings("unchecked")
    private Flow fluxo() {
        ObjectProvider<FlowEngine> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenAnswer(i -> motorDoPasso != null ? motorDoPasso : engine);
        DefaultFlowStepFactory factory = new DefaultFlowStepFactory(new DefaultComponentCatalog(),
                mock(DynamicWorkflowService.class), null, null, provider);
        FlowStep agente = factory.create(Map.of("id", "agente", "type", "agent", "componentId", "mcp-agent",
                "configuration", Map.of("systemPrompt", "Você faz estornos.",
                        "tools", List.of(TOOL), "aprovacaoHumana", List.of(TOOL)),
                "connections", List.of(Map.of("sourceId", "agente", "targetId", "final"))));
        FlowStep fim = new FlowStep() {
            @Override public String getId() { return "final"; }
            @Override public StepType getType() { return StepType.TOOL; }
            @Override public List<StepConnection> getConnections() { return List.of(); }
            @Override public CompletableFuture<StepResult> execute(ExecutionContext c) {
                entradasDoFinal.add(c.get("input").orElse(null));
                return CompletableFuture.completedFuture(SimpleStepResult.ok("final", "mensagem cordial", 1));
            }
        };
        Flow flow = new SimpleFlow(FLOW_ID, new FlowMetadata("Estorno", "", "1.0.0", null, null, List.of()),
                List.of(agente, fim));
        flowRepository.save(flow);
        return flow;
    }

    private FlowResult executar() throws Exception {
        ExecutionContext ctx = new DefaultExecutionContext(TENANT, "operador", FLOW_ID,
                MessageWindowChatMemory.builder().maxMessages(20).build());
        ctx.set("input", "Faça o estorno do pedido PED-777");
        McpAgentHost.inject(ctx, host);
        return engine.execute(fluxo(), ctx).get(15, TimeUnit.SECONDS);
    }

    private ApprovalResponse unicoPendente() {
        List<ApprovalResponse> pendentes = fila.listPending("all");
        assertThat(pendentes).as("um pedido só: o portão da execução, sem o laço duplicado").hasSize(1);
        return pendentes.get(0);
    }

    @Test
    @DisplayName("a tool espera: execução PAUSED/AWAITING_APPROVAL, nó seguinte não roda, pedido na fila")
    void segura() throws Exception {
        FlowResult resultado = executar();

        assertThat(resultado.getStatus()).as("não é conclusão").isEqualTo(ExecutionStatus.PAUSED);
        assertThat(stateManager.loadState(FLOW_ID).getStatus()).isEqualTo(FlowStatus.AWAITING_APPROVAL);
        assertThat(entradasDoFinal).as("o nó seguinte não roda sobre uma resposta que não existe").isEmpty();
        assertThat(host.servidor.chamadas).as("nada executado antes da decisão").isEmpty();

        ApprovalResponse pedido = unicoPendente();
        assertThat(pedido.flowId()).as("a execução, não o UUID da rodada do laço").isEqualTo(FLOW_ID);
        assertThat(pedido.stepId()).isEqualTo("agente");
        assertThat(pedido.proposal()).as("os argumentos da tool").isEqualTo(Map.of("pedido", "PED-777"));
        assertThat(pedido.description()).contains(TOOL);
        assertThat(pedido.expiresAt()).as("o prazo global vale para o portão do agente").isNotNull();
        assertThat(fila.listPending(TENANT)).extracting(ApprovalResponse::requestId)
                .containsExactly(pedido.requestId());
    }

    @Test
    @DisplayName("aprovar executa a tool, o laço continua, o fluxo segue e o aprovador fica gravado")
    void aprova() throws Exception {
        executar();
        ApprovalResponse pedido = unicoPendente();

        fila.submitDecision(pedido.requestId(),
                new ApprovalSubmitRequest(TENANT, "APPROVED", null, "diretor", "estorno conferido"));
        esperar(() -> stateManager.loadState(FLOW_ID).getStatus() == FlowStatus.COMPLETED);

        assertThat(host.servidor.chamadas).containsExactly(Map.of("pedido", "PED-777"));
        assertThat(entradasDoFinal).singleElement().satisfies(entrada -> assertThat(entrada)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("text", "Estorno do pedido feito")
                .containsEntry("suspended", false));
        ApprovalResponse decidido = fila.getDetail(pedido.requestId(), FLOW_ID);
        assertThat(decidido.status()).isEqualTo("APPROVED");
        assertThat(decidido.decidedBy()).isEqualTo("diretor");
        assertThat(decidido.comment()).isEqualTo("estorno conferido");
        assertThat(fila.listPending("all")).isEmpty();
        assertThat(lacos.findPendingByTenant("all")).as("o laço concluiu e saiu do store").isEmpty();
    }

    @Test
    @DisplayName("EDITED troca os argumentos que a tool recebe")
    void edita() throws Exception {
        executar();
        ApprovalResponse pedido = unicoPendente();

        fila.submitDecision(pedido.requestId(), new ApprovalSubmitRequest(TENANT, "EDITED",
                Map.of("pedido", "PED-778"), "diretor", "pedido certo"));
        esperar(() -> stateManager.loadState(FLOW_ID).getStatus() == FlowStatus.COMPLETED);

        assertThat(host.servidor.chamadas).containsExactly(Map.of("pedido", "PED-778"));
        assertThat(entradasDoFinal).as("o nó seguinte recebe a resposta do laço retomado").singleElement()
                .satisfies(entrada -> assertThat(entrada)
                        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                        .containsEntry("text", "Estorno do pedido feito"));
        assertThat(fila.getDetail(pedido.requestId(), FLOW_ID).status()).isEqualTo("EDITED");
    }

    @Test
    @DisplayName("EDITED com algo que não são argumentos é recusado e o pedido continua pendente")
    void edicaoInvalida() throws Exception {
        executar();
        ApprovalResponse pedido = unicoPendente();

        assertThatThrownBy(() -> fila.submitDecision(pedido.requestId(),
                new ApprovalSubmitRequest(TENANT, "EDITED", "estorna aí", "diretor")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("editedPayload");
        assertThat(unicoPendente().requestId()).isEqualTo(pedido.requestId());
    }

    @Test
    @DisplayName("recusar para o fluxo sem executar a tool e descarta o laço")
    void recusa() throws Exception {
        executar();
        ApprovalResponse pedido = unicoPendente();

        fila.submitDecision(pedido.requestId(),
                new ApprovalSubmitRequest(TENANT, "REJECTED", null, "diretor", "não autorizado"));

        assertThat(stateManager.loadState(FLOW_ID).getStatus()).isEqualTo(FlowStatus.STOPPED);
        assertThat(entradasDoFinal).isEmpty();
        assertThat(host.servidor.chamadas).isEmpty();
        assertThat(fila.listPending("all")).isEmpty();
        assertThat(lacos.findPendingByTenant("all")).as("ninguém mais retoma aquele laço").isEmpty();
        assertThat(fila.getDetail(pedido.requestId(), FLOW_ID).decidedBy()).isEqualTo("diretor");
    }

    @Test
    @DisplayName("o laço de um nó não é decidível pela fila avulsa, nem pelo id dele")
    void lacoDoFluxoNaoEAvulso() throws Exception {
        executar();
        String laco = lacos.findPendingByTenant("all").get(0).pending().requestId();

        assertThat(retomada.pendentes("all")).isEmpty();
        assertThat(retomada.detalhe(laco)).isEmpty();
        assertThatThrownBy(() -> retomada.decidir(laco, true, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(FLOW_ID);
        assertThat(host.servidor.chamadas).isEmpty();
    }

    @Test
    @DisplayName("o portão que não abre falha o passo e apaga o laço que o runner já gravara")
    void portaoQueNaoAbre() throws Exception {
        motorDoPasso = (FlowEngine) java.lang.reflect.Proxy.newProxyInstance(
                FlowEngine.class.getClassLoader(), new Class<?>[] {FlowEngine.class}, (proxy, metodo, args) -> {
                    if (metodo.getName().equals("requestApproval")) {
                        throw new IllegalStateException("store de estado fora do ar");
                    }
                    try {
                        return metodo.invoke(engine, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });

        FlowResult resultado = executar();

        assertThat(resultado.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(entradasDoFinal).isEmpty();
        assertThat(host.servidor.chamadas).isEmpty();
        assertThat(lacos.findPendingByTenant("all"))
                .as("sem portão, ninguém decide nem retoma o laço: ele não pode ficar no store").isEmpty();
    }

    @Test
    @DisplayName("a retomada aprovada que falha antes de consumir o laço o apaga do store")
    void retomadaQueFalha() throws Exception {
        executar();
        ApprovalResponse pedido = unicoPendente();
        host.semCliente = true;

        fila.submitDecision(pedido.requestId(),
                new ApprovalSubmitRequest(TENANT, "APPROVED", null, "diretor", "pode estornar"));
        esperar(() -> stateManager.loadState(FLOW_ID).getStatus() == FlowStatus.FAILED);

        assertThat(host.servidor.chamadas).isEmpty();
        assertThat(entradasDoFinal).isEmpty();
        assertThat(lacos.findPendingByTenant("all"))
                .as("LACO_PENDENTE já foi consumido: o laço ficaria órfão").isEmpty();
    }

    @Test
    @DisplayName("cancelar a execução enquanto o agente roda não deixa o laço órfão no store")
    void cancelada() throws Exception {
        host.modelo.noPrimeiroTurno = () -> engine.cancel(FLOW_ID);

        FlowResult resultado = executar();

        assertThat(resultado.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(stateManager.loadState(FLOW_ID).getStatus()).isEqualTo(FlowStatus.STOPPED);
        assertThat(fila.listPending("all")).isEmpty();
        assertThat(entradasDoFinal).isEmpty();
        assertThat(host.servidor.chamadas).isEmpty();
        assertThat(lacos.findPendingByTenant("all"))
                .as("fora de todas as filas e sem quem o retome: o laço sai do store").isEmpty();
    }

    @Test
    @DisplayName("o formulário de uma conversa não aprova a tool do agente: o pedido continua na fila")
    void formularioNaoAprovaATool() throws Exception {
        executar();
        ApprovalResponse pedido = unicoPendente();

        assertThatThrownBy(() -> fila.requireAwaitingInput(TENANT, FLOW_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tool");
        assertThatThrownBy(() -> fila.submitInput(TENANT, FLOW_ID,
                Map.of("subject", "reembolso"), "cliente", "Formulário support-ticket enviado"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(unicoPendente().requestId()).isEqualTo(pedido.requestId());
        assertThat(stateManager.loadState(FLOW_ID).getStatus()).isEqualTo(FlowStatus.AWAITING_APPROVAL);
        assertThat(host.servidor.chamadas).isEmpty();
        assertThat(lacos.findPendingByTenant("all")).hasSize(1);
    }

    private static void esperar(java.util.function.BooleanSupplier condicao) throws Exception {
        long limite = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < limite) {
            if (condicao.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("condição não satisfeita dentro do prazo");
    }
}
