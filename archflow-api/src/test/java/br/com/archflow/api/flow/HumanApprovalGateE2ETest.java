package br.com.archflow.api.flow;

import br.com.archflow.agent.persistence.InMemoryFlowRepository;
import br.com.archflow.api.approval.dto.ApprovalResponse;
import br.com.archflow.api.approval.dto.ApprovalSubmitRequest;
import br.com.archflow.api.approval.impl.ApprovalQueueService;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.core.StateManager;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.engine.ExecutionKeys;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowMetadata;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.model.flow.FlowStatus;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.model.flow.StepType;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prova que o gate human-in-the-loop é alcançável ponta a ponta:
 * step APPROVAL → suspensão durável → fila de aprovações → decisão → retomada.
 *
 * <p>Cada peça já existia isolada e nenhuma se encontrava: {@code requestApproval}
 * não tinha produtor (nenhum {@code StepType} o chamava), {@code submitApproval}
 * não tinha endpoint, e a fila exposta na API lia um registry em memória sem
 * produtor. Este teste percorre o circuito inteiro.
 */
@DisplayName("Gate de aprovação humana — circuito completo")
class HumanApprovalGateE2ETest {

    private static final String FLOW_ID = "remediation-flow";

    private FlowRepository flowRepository;
    private StateManager stateManager;
    private FlowEngine engine;
    private ApprovalQueueService queue;
    private AtomicInteger afterGateRuns;

    @BeforeEach
    void setUp() {
        flowRepository = new InMemoryFlowRepository();
        stateManager = new InMemoryStateManager();
        engine = FlowEngineFactory.create(flowRepository, null, null, stateManager);
        queue = new ApprovalQueueService(stateManager, engine);
        afterGateRuns = new AtomicInteger();
    }

    /** Step que conta execuções — representa a remediação que só pode rodar após o "sim". */
    private FlowStep countingStep(String id) {
        return new FlowStep() {
            @Override public String getId() { return id; }
            @Override public StepType getType() { return StepType.TOOL; }
            @Override public List<StepConnection> getConnections() { return List.of(); }
            @Override public CompletableFuture<StepResult> execute(ExecutionContext c) {
                afterGateRuns.incrementAndGet();
                return CompletableFuture.completedFuture(SimpleStepResult.ok(id, "remediado", 1));
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

    /** gate (APPROVAL) → remediar. */
    private Flow flowWithGate() {
        HumanApprovalStep gate = new HumanApprovalStep(
                "gate",
                List.of(edge("gate", "remediar")),
                Map.of("proposal", Map.of("acao", "reiniciar traefik"),
                        "description", "Reiniciar o serviço traefik"),
                () -> engine);
        Flow flow = new SimpleFlow(FLOW_ID,
                new FlowMetadata("Remediação", "", "1.0.0", null, null, List.of()),
                List.of(gate, countingStep("remediar")));
        flowRepository.save(flow);
        return flow;
    }

    private ExecutionContext context() {
        return new DefaultExecutionContext("acme", "sre-1", FLOW_ID,
                MessageWindowChatMemory.builder().maxMessages(5).build());
    }

    @Test
    @DisplayName("suspende no gate, aparece na fila com a proposta e retoma ao aprovar")
    void suspendsThenResumesOnApproval() throws Exception {
        FlowResult first = engine.execute(flowWithGate(), context()).get(15, TimeUnit.SECONDS);

        // (1) o fluxo parou no gate — a remediação NÃO rodou
        assertThat(first.getStatus()).isEqualTo(ExecutionStatus.PAUSED);
        assertThat(afterGateRuns).hasValue(0);
        assertThat(stateManager.loadState(FLOW_ID).getStatus())
                .isEqualTo(FlowStatus.AWAITING_APPROVAL);

        // (2) a fila enxerga a solicitação, com a proposta que o motor descartava
        List<ApprovalResponse> pending = queue.listPending("acme");
        assertThat(pending).hasSize(1);
        ApprovalResponse request = pending.get(0);
        assertThat(request.flowId()).isEqualTo(FLOW_ID);
        assertThat(request.stepId()).isEqualTo("gate");
        assertThat(request.description()).isEqualTo("Reiniciar o serviço traefik");
        assertThat(request.proposal()).isEqualTo(Map.of("acao", "reiniciar traefik"));
        assertThat(request.createdAt()).isNotNull();

        // (3) o humano aprova → o fluxo retoma e a remediação roda exatamente uma vez
        queue.submitDecision(request.requestId(),
                new ApprovalSubmitRequest("acme", "APPROVED", null, "sre-1"));

        waitUntil(() -> afterGateRuns.get() == 1);
        assertThat(afterGateRuns)
                .as("o gate não pode reexecutar nem duplicar a remediação")
                .hasValue(1);

        // (4) a decisão fica registrada — com quem decidiu, para a auditoria
        assertThat(stateManager.loadState(FLOW_ID).getVariables())
                .containsEntry(ExecutionKeys.APPROVAL_DECISION, "APPROVED")
                .containsEntry(ExecutionKeys.APPROVAL_DECIDED_BY, "sre-1")
                .containsKey(ExecutionKeys.APPROVAL_DECIDED_AT);
        assertThat(queue.listPending("acme")).isEmpty();
    }

    @Test
    @DisplayName("a conversa sobrevive à suspensão — o agente não volta amnésico")
    void chatMemorySurvivesTheGate() throws Exception {
        List<String> vistoDepois = new java.util.ArrayList<>();

        // Step que conversa antes do gate, e outro que lê a conversa depois.
        FlowStep conversa = new FlowStep() {
            @Override public String getId() { return "conversa"; }
            @Override public StepType getType() { return StepType.TOOL; }
            @Override public List<StepConnection> getConnections() {
                return List.of(edge("conversa", "gate"));
            }
            @Override public CompletableFuture<StepResult> execute(ExecutionContext c) {
                c.getChatMemory().add(
                        dev.langchain4j.data.message.UserMessage.from("reinicia o traefik?"));
                c.getChatMemory().add(
                        dev.langchain4j.data.message.AiMessage.from("proponho reiniciar"));
                return CompletableFuture.completedFuture(SimpleStepResult.ok("conversa", "ok", 1));
            }
        };
        FlowStep depois = new FlowStep() {
            @Override public String getId() { return "remediar"; }
            @Override public StepType getType() { return StepType.TOOL; }
            @Override public List<StepConnection> getConnections() { return List.of(); }
            @Override public CompletableFuture<StepResult> execute(ExecutionContext c) {
                c.getChatMemory().messages().forEach(m -> vistoDepois.add(m.getClass().getSimpleName()));
                afterGateRuns.incrementAndGet();
                return CompletableFuture.completedFuture(SimpleStepResult.ok("remediar", "ok", 1));
            }
        };
        HumanApprovalStep gate = new HumanApprovalStep("gate",
                List.of(edge("gate", "remediar")), Map.of("proposal", "reiniciar"), () -> engine);
        Flow flow = new SimpleFlow(FLOW_ID,
                new FlowMetadata("Conversa", "", "1.0.0", null, null, List.of()),
                List.of(conversa, gate, depois));
        flowRepository.save(flow);

        engine.execute(flow, context()).get(15, TimeUnit.SECONDS);
        ApprovalResponse request = queue.listPending("acme").get(0);
        queue.submitDecision(request.requestId(),
                new ApprovalSubmitRequest("acme", "APPROVED", null, "sre-1"));
        waitUntil(() -> afterGateRuns.get() == 1);

        assertThat(vistoDepois)
                .as("o resume cria uma ChatMemory nova; sem restauracao a conversa sumia")
                .containsExactly("UserMessage", "AiMessage");
    }

    @Test
    @DisplayName("recusar encerra o fluxo sem executar a remediação")
    void rejectionStopsTheFlow() throws Exception {
        engine.execute(flowWithGate(), context()).get(15, TimeUnit.SECONDS);

        ApprovalResponse request = queue.listPending("acme").get(0);
        queue.submitDecision(request.requestId(),
                new ApprovalSubmitRequest("acme", "REJECTED", null, "sre-1"));

        assertThat(afterGateRuns)
                .as("uma remediação recusada jamais pode rodar")
                .hasValue(0);
        assertThat(stateManager.loadState(FLOW_ID).getStatus()).isEqualTo(FlowStatus.STOPPED);
        assertThat(queue.listPending("acme")).isEmpty();
    }

    @Test
    @DisplayName("aprovar com edição entrega o payload editado ao fluxo retomado")
    void editedPayloadReachesTheResumedFlow() throws Exception {
        engine.execute(flowWithGate(), context()).get(15, TimeUnit.SECONDS);

        ApprovalResponse request = queue.listPending("acme").get(0);
        queue.submitDecision(request.requestId(), new ApprovalSubmitRequest(
                "acme", "EDITED", Map.of("acao", "reiniciar traefik com drain"), "sre-1"));

        waitUntil(() -> afterGateRuns.get() == 1);
        assertThat(stateManager.loadState(FLOW_ID).getVariables())
                .containsEntry(ExecutionKeys.APPROVAL_PAYLOAD,
                        Map.of("acao", "reiniciar traefik com drain"));
    }

    /**
     * A retomada roda em virtual thread disparada por submitApproval; o teste
     * espera a condição em vez de dormir por um tempo fixo.
     */
    private static void waitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("condição não satisfeita dentro do timeout");
    }
}
