package br.com.archflow.api.flow;

import br.com.archflow.agent.persistence.InMemoryFlowRepository;
import br.com.archflow.api.approval.dto.ApprovalResponse;
import br.com.archflow.api.approval.dto.ApprovalSubmitRequest;
import br.com.archflow.api.approval.impl.ApprovalQueueService;
import br.com.archflow.api.web.workflow.InMemoryWorkflowRuntimeStore;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.core.StateManager;
import br.com.archflow.engine.lifecycle.FlowLifecycleListeners;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O nó de aprovação do designer chega inteiro à fila e ao prazo (RCHFLW-60), o portão à espera
 * não aparece como concluído (RCHFLW-32) e a resposta da decisão diz quem decidiu a partir do
 * estado durável (RCHFLW-38).
 *
 * <p>O designer grava {@code approvalTitle}, {@code approvalDescription}, {@code approvalProposal}
 * (texto), {@code approvalTimeoutMinutes} e {@code approvalOnTimeout}; o passo só lia
 * {@code proposal} e {@code description}. A fila mostrava a descrição vazia, a saída do passo
 * anterior no lugar da proposta e o prazo global no lugar do do nó.</p>
 */
@DisplayName("Nó de aprovação — campos do designer, portão aguardando e quem decidiu")
class ApprovalNodeFieldsE2ETest {

    private FlowRepository flowRepository;
    private StateManager stateManager;
    private FlowEngine engine;
    private InMemoryWorkflowRuntimeStore records;
    private AtomicInteger afterGateRuns;
    private StepRecordingListener stepRecorder;
    /** O id da execução no registro — e do fluxo, como em /execute e no AG-UI. */
    private String flowId;

    @BeforeEach
    void setUp() {
        flowRepository = new InMemoryFlowRepository();
        stateManager = new InMemoryStateManager();
        records = new InMemoryWorkflowRuntimeStore();
        flowId = String.valueOf(records.createExecution("wf-1", "Desconto").get("id"));
        // O registro de passos de verdade, ligado como o servidor liga: é ele que o detalhe da
        // execução lê.
        stepRecorder = new StepRecordingListener(records);
        FlowLifecycleListeners.register(stepRecorder);
        engine = FlowEngineFactory.create(flowRepository, null, null, stateManager);
        afterGateRuns = new AtomicInteger();
    }

    @AfterEach
    void tearDown() {
        FlowLifecycleListeners.unregister(stepRecorder);
    }

    private ApprovalQueueService queueAt(Duration globalTimeout, Clock clock) {
        return new ApprovalQueueService(stateManager, engine, globalTimeout, clock, null, records);
    }

    private FlowStep countingStep(String id) {
        return new FlowStep() {
            @Override public String getId() { return id; }
            @Override public StepType getType() { return StepType.TOOL; }
            @Override public List<StepConnection> getConnections() { return List.of(); }
            @Override public CompletableFuture<StepResult> execute(ExecutionContext c) {
                afterGateRuns.incrementAndGet();
                return CompletableFuture.completedFuture(SimpleStepResult.ok(id, "aplicado", 1));
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

    /** gate (config do designer) → aplicar, registrado como o /execute registra. */
    private Flow flowWithGate(Map<String, Object> gateConfig) {
        HumanApprovalStep gate = new HumanApprovalStep("gate", List.of(edge("gate", "aplicar")),
                gateConfig, () -> engine);
        Flow flow = new SimpleFlow(flowId, new FlowMetadata("Desconto", "", "1.0.0", null, null, List.of()),
                List.of(gate, countingStep("aplicar")));
        flowRepository.save(flow);
        return flow;
    }

    /** Exatamente o que o PropertyPanel grava no nó (a proposta é texto). */
    private static Map<String, Object> designerConfig(String onTimeout) {
        Map<String, Object> config = new HashMap<>();
        config.put("approvalTitle", "Revisar desconto");
        config.put("approvalDescription", "Confirme o desconto de 15%");
        config.put("approvalProposal", "{\"desconto\": 15}");
        config.put("approvalTimeoutMinutes", 1);
        config.put("approvalOnTimeout", onTimeout);
        return config;
    }

    private ExecutionContext context() {
        ExecutionContext ctx = new DefaultExecutionContext("acme", "u", flowId,
                MessageWindowChatMemory.builder().maxMessages(5).build());
        // A saída do passo anterior: não é ela que o revisor deve ver quando o nó tem proposta.
        ctx.set(ComponentStep.INPUT_KEY, Map.of("pedido", 42));
        return ctx;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> stepRecord(String stepId) {
        Map<String, Object> execution = records.getExecution(flowId);
        List<Map<String, Object>> steps = (List<Map<String, Object>>) execution.get("steps");
        return steps.stream().filter(s -> stepId.equals(s.get("stepId"))).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("título, descrição, proposta (JSON) e prazo do nó chegam à fila")
    void designerFieldsReachTheQueue() throws Exception {
        ApprovalQueueService queue = queueAt(Duration.ofHours(24), Clock.systemUTC());
        FlowResult first = engine.execute(flowWithGate(designerConfig("reject")), context())
                .get(15, TimeUnit.SECONDS);

        assertThat(first.getStatus()).isEqualTo(ExecutionStatus.PAUSED);
        ApprovalResponse pending = queue.listPending("acme").get(0);
        assertThat(pending.title()).isEqualTo("Revisar desconto");
        assertThat(pending.description()).isEqualTo("Confirme o desconto de 15%");
        // O objeto digitado, não a string com aspas escapadas nem a saída do passo anterior
        assertThat(pending.proposal()).isEqualTo(Map.of("desconto", 15));
        // O prazo do nó (1 min), não o global (24 h)
        assertThat(Duration.between(pending.createdAt(), pending.expiresAt())).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("vencido o prazo do nó, recusa — mesmo com 'aprovar no timeout' configurado")
    void nodeTimeoutRejectsEvenWhenTheNodeAsksToApprove() throws Exception {
        engine.execute(flowWithGate(designerConfig("approve")), context()).get(15, TimeUnit.SECONDS);
        // Dois minutos depois: venceu o prazo do nó (1 min), não o global (24 h).
        ApprovalQueueService later = queueAt(Duration.ofHours(24),
                Clock.fixed(Instant.now().plus(Duration.ofMinutes(2)), ZoneOffset.UTC));

        assertThat(later.expireStale()).isEqualTo(1);

        var state = stateManager.loadState(flowId);
        assertThat(state.getStatus()).isEqualTo(FlowStatus.STOPPED);
        assertThat(state.getVariables())
                .as("'ninguém olhou' nunca vira 'pode executar'")
                .containsEntry(ExecutionKeys.APPROVAL_DECISION, "REJECTED")
                .containsEntry(ExecutionKeys.APPROVAL_DECIDED_BY, "system:timeout");
        assertThat(afterGateRuns).hasValue(0);
    }

    @Test
    @DisplayName("o prazo do nó vale mesmo com o prazo global desligado (PT0S)")
    void nodeTimeoutWorksWithoutGlobalTimeout() throws Exception {
        engine.execute(flowWithGate(designerConfig("reject")), context()).get(15, TimeUnit.SECONDS);
        ApprovalQueueService later = queueAt(Duration.ZERO,
                Clock.fixed(Instant.now().plus(Duration.ofMinutes(2)), ZoneOffset.UTC));

        assertThat(later.expireStale()).isEqualTo(1);
        assertThat(stateManager.loadState(flowId).getStatus()).isEqualTo(FlowStatus.STOPPED);
    }

    @Test
    @DisplayName("o portão à espera fica PAUSED no registro e só conclui depois da decisão, com quem decidiu")
    void waitingGateIsNotCompletedAndTheResponseSaysWhoDecided() throws Exception {
        ApprovalQueueService queue = queueAt(Duration.ofHours(24), Clock.systemUTC());
        engine.execute(flowWithGate(designerConfig("reject")), context()).get(15, TimeUnit.SECONDS);

        // RCHFLW-32: aguardando, não ✓
        assertThat(stepRecord("gate").get("status")).isEqualTo("PAUSED");
        assertThat(String.valueOf(stepRecord("gate").get("output"))).contains("requestId");
        Object completed = stateManager.loadState(flowId).getVariables().get("__archflow.completedSteps");
        List<Object> completedSteps = completed instanceof List<?> list ? List.copyOf(list) : List.of();
        assertThat(completedSteps).as("o portão não está concluído enquanto espera").doesNotContain("gate");

        String requestId = queue.listPending("acme").get(0).requestId();
        // O corpo diz um nome; a resposta devolve o que o motor gravou (o controller já trocou
        // o decisor pelo autenticado antes de chegar aqui).
        ApprovalResponse decided = queue.submitDecision(requestId,
                new ApprovalSubmitRequest("acme", "APPROVED", null, "maria", "desconto dentro da política"));

        assertThat(decided.decidedBy()).isEqualTo("maria");
        assertThat(decided.comment()).isEqualTo("desconto dentro da política");
        assertThat(decided.decidedAt()).isNotNull();
        assertThat(decided.title()).isEqualTo("Revisar desconto");

        waitUntil(() -> afterGateRuns.get() == 1);
        waitUntil(() -> "COMPLETED".equals(stepRecord("gate").get("status")));
        assertThat(String.valueOf(stepRecord("gate").get("output"))).contains("APPROVED");
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("condição não satisfeita em 10s");
            }
            Thread.sleep(20);
        }
    }
}
