package br.com.archflow.api.flow;

import br.com.archflow.agent.persistence.InMemoryFlowRepository;
import br.com.archflow.api.approval.dto.ApprovalSubmitRequest;
import br.com.archflow.api.approval.impl.ApprovalQueueService;
import br.com.archflow.api.web.workflow.InMemoryWorkflowRuntimeStore;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.core.StateManager;
import br.com.archflow.engine.lifecycle.FlowLifecycleListeners;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowMetadata;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.model.flow.StepType;
import br.com.archflow.model.tenant.DefaultTenant;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RCHFLW-99: numa execução que pausou numa aprovação e foi retomada, o detalhe mostrava "1/3 Rascunho",
 * "1/3 Revisão", "2/3 Final" — o motor recomeçava a contagem em 0 na retomada — e a duração cobria só o
 * trecho depois da retomada. O teste roda o motor de verdade com o registro de passos que o servidor
 * liga ({@link StepRecordingListener}): rascunho → revisão (portão) → final, aprovado pela fila.
 */
@DisplayName("Retomada da aprovação: numeração dos passos e duração da execução")
class NumeracaoDosPassosNaRetomadaTest {

    /** Quanto o portão espera pela decisão: a duração da execução tem de cobrir esse tempo. */
    private static final long ESPERA_MS = 400;

    private FlowRepository flowRepository;
    private StateManager stateManager;
    private FlowEngine engine;
    private InMemoryWorkflowRuntimeStore records;
    private StepRecordingListener stepRecorder;
    private String flowId;

    @BeforeEach
    void setUp() {
        flowRepository = new InMemoryFlowRepository();
        stateManager = new InMemoryStateManager();
        records = new InMemoryWorkflowRuntimeStore();
        flowId = String.valueOf(records.createExecution(DefaultTenant.ID, "wf-1", "Revisão").get("id"));
        stepRecorder = new StepRecordingListener(records);
        FlowLifecycleListeners.register(stepRecorder);
        engine = FlowEngineFactory.create(flowRepository, null, null, stateManager);
    }

    @AfterEach
    void tearDown() {
        FlowLifecycleListeners.unregister(stepRecorder);
    }

    private static FlowStep passo(String id, List<StepConnection> saidas) {
        return new FlowStep() {
            @Override public String getId() { return id; }
            @Override public StepType getType() { return StepType.TOOL; }
            @Override public List<StepConnection> getConnections() { return saidas; }
            @Override public CompletableFuture<StepResult> execute(ExecutionContext c) {
                return CompletableFuture.completedFuture(SimpleStepResult.ok(id, "saída de " + id, 1));
            }
        };
    }

    private static StepConnection aresta(String from, String to) {
        return new StepConnection() {
            @Override public String getSourceId() { return from; }
            @Override public String getTargetId() { return to; }
            @Override public Optional<String> getCondition() { return Optional.empty(); }
            @Override public boolean isErrorPath() { return false; }
        };
    }

    @SuppressWarnings("unchecked")
    private Optional<Map<String, Object>> talvez(String stepId) {
        List<Map<String, Object>> steps = (List<Map<String, Object>>) records.getExecution(DefaultTenant.ID, flowId)
                .get("steps");
        return steps == null ? Optional.empty()
                : steps.stream().filter(s -> stepId.equals(s.get("stepId"))).findFirst();
    }

    private Map<String, Object> registro(String stepId) {
        return talvez(stepId).orElseThrow();
    }

    @Test
    @DisplayName("depois da retomada a numeração continua (1/3, 2/3, 3/3) e a duração cobre a espera")
    void numeracaoEDuracaoContinuam() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("approvalTitle", "Revisar");
        HumanApprovalStep revisao = new HumanApprovalStep("revisao", List.of(aresta("revisao", "final")), config,
                () -> engine);
        Flow flow = new SimpleFlow(flowId, new FlowMetadata("Revisão", "", "1.0.0", null, null, List.of()),
                List.of(passo("rascunho", List.of(aresta("rascunho", "revisao"))), revisao,
                        passo("final", List.of())));
        flowRepository.save(flow);
        ExecutionContext ctx = new DefaultExecutionContext("acme", "u", flowId,
                MessageWindowChatMemory.builder().maxMessages(5).build());

        assertThat(engine.execute(flow, ctx).get(15, TimeUnit.SECONDS).getStatus())
                .isEqualTo(ExecutionStatus.PAUSED);
        assertThat(registro("revisao").get("stepIndex")).isEqualTo(1);

        Thread.sleep(ESPERA_MS);
        ApprovalQueueService fila = new ApprovalQueueService(stateManager, engine, Duration.ofHours(24),
                Clock.systemUTC(), null, records);
        fila.submitDecision(fila.listPending("acme").get(0).requestId(),
                new ApprovalSubmitRequest("acme", "APPROVED", null, "maria", null));
        esperar(() -> talvez("final").map(r -> "COMPLETED".equals(r.get("status"))).orElse(false));
        esperar(() -> records.getExecution(DefaultTenant.ID, flowId).get("duration") != null);

        assertThat(List.of(registro("rascunho"), registro("revisao"), registro("final")))
                .extracting(r -> r.get("stepIndex"), r -> r.get("stepCount"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(0, 3),
                        org.assertj.core.groups.Tuple.tuple(1, 3),
                        org.assertj.core.groups.Tuple.tuple(2, 3));
        assertThat(((Number) records.getExecution(DefaultTenant.ID, flowId).get("duration")).longValue())
                .as("a duração cobre a execução inteira, inclusive a espera pela decisão")
                .isGreaterThanOrEqualTo(ESPERA_MS);
    }

    /**
     * RCHFLW-188, item 17: na retomada o {@code startedAt} do portão era sobrescrito (ficava depois do
     * {@code suspendedAt}), a duração era só a da reexecução (3 ms para minutos de espera), e só o portão recusado
     * dizia quem decidiu.
     */
    @Test
    @DisplayName("o portão aprovado guarda o início de antes da pausa, a espera na duração e quem aprovou")
    void portaoAprovadoGuardaInicioEsperaEDecisor() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("approvalTitle", "Revisar");
        HumanApprovalStep revisao = new HumanApprovalStep("revisao", List.of(aresta("revisao", "final")), config,
                () -> engine);
        Flow flow = new SimpleFlow(flowId, new FlowMetadata("Revisão", "", "1.0.0", null, null, List.of()),
                List.of(passo("rascunho", List.of(aresta("rascunho", "revisao"))), revisao,
                        passo("final", List.of())));
        flowRepository.save(flow);
        ExecutionContext ctx = new DefaultExecutionContext("acme", "u", flowId,
                MessageWindowChatMemory.builder().maxMessages(5).build());
        engine.execute(flow, ctx).get(15, TimeUnit.SECONDS);
        Object inicio = registro("revisao").get("startedAt");

        Thread.sleep(ESPERA_MS);
        ApprovalQueueService fila = new ApprovalQueueService(stateManager, engine, Duration.ofHours(24),
                Clock.systemUTC(), null, records);
        fila.submitDecision(fila.listPending("acme").get(0).requestId(),
                new ApprovalSubmitRequest("acme", "APPROVED", null, "maria", null));
        esperar(() -> talvez("final").map(r -> "COMPLETED".equals(r.get("status"))).orElse(false));

        Map<String, Object> portao = registro("revisao");
        assertThat(portao.get("status")).isEqualTo("COMPLETED");
        assertThat(portao.get("startedAt")).as("o início é o de antes da pausa").isEqualTo(inicio);
        assertThat(java.time.Instant.parse(String.valueOf(portao.get("startedAt"))))
                .isBeforeOrEqualTo(java.time.Instant.parse(String.valueOf(portao.get("suspendedAt"))));
        assertThat(portao.get("resumedAt")).as("a retomada fica registrada à parte").isNotNull();
        assertThat(((Number) portao.get("durationMs")).longValue()).as("a duração cobre a espera")
                .isGreaterThanOrEqualTo(ESPERA_MS);
        assertThat(portao.get("decidedBy")).as("quem aprovou, como no recusado").isEqualTo("maria");
    }

    private static void esperar(BooleanSupplier condicao) throws Exception {
        long prazo = System.currentTimeMillis() + 10_000;
        while (!condicao.getAsBoolean()) {
            if (System.currentTimeMillis() > prazo) {
                throw new AssertionError("condição não satisfeita em 10s");
            }
            Thread.sleep(20);
        }
    }
}
