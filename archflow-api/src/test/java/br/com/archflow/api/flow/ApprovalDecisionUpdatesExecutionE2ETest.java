package br.com.archflow.api.flow;

import br.com.archflow.model.tenant.DefaultTenant;

import br.com.archflow.agent.persistence.InMemoryFlowRepository;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.api.agent.mcp.McpAgentHost;
import br.com.archflow.api.agui.AgUiController;
import br.com.archflow.api.agui.DefaultAgUiEventMapper;
import br.com.archflow.api.agui.RunAgentInput;
import br.com.archflow.api.approval.dto.ApprovalResponse;
import br.com.archflow.api.approval.dto.ApprovalSubmitRequest;
import br.com.archflow.api.approval.impl.ApprovalQueueService;
import br.com.archflow.api.audit.AuditTrail;
import br.com.archflow.api.web.workflow.InMemoryWorkflowRuntimeStore;
import br.com.archflow.api.web.workflow.SpringWorkflowCrudController;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.core.StateManager;
import br.com.archflow.engine.exceptions.FlowEngineException;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.engine.ExecutionKeys;
import br.com.archflow.model.enums.ExecutionStatus;
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
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A decisão tomada pela fila de aprovações chega ao registro da execução — o que
 * {@code GET /api/executions/{id}} e as telas de Execuções leem — e uma execução iniciada pelo
 * editor (AG-UI) retoma ao ser aprovada.
 *
 * <p>Antes, quem disparava a execução gravava PAUSED quando ela parava no portão e ninguém mais
 * tocava no registro: aprovar, recusar ou deixar vencer deixava a execução PAUSED para sempre, sem
 * {@code completedAt} (RCHFLW-40). E o AG-UI não registrava o fluxo no {@link FlowRepository}: aprovar
 * consumia o pedido, a retomada falhava numa future que ninguém olhava e a fila respondia sucesso
 * (RCHFLW-59).</p>
 *
 * <p>Os pontos de entrada são os controllers de verdade, com o motor de verdade; só o
 * desserializador é trocado por um que monta {@code gate → remediar} — o que importa aqui é o
 * caminho da decisão, não a leitura do JSON.</p>
 */
@DisplayName("Decisão pela fila atualiza o registro da execução")
class ApprovalDecisionUpdatesExecutionE2ETest {

    private FlowRepository flowRepository;
    private StateManager stateManager;
    private FlowEngine engine;
    private InMemoryWorkflowRuntimeStore store;
    private ApprovalQueueService queue;
    private SpringWorkflowCrudController crud;
    private AgUiController agUi;
    private AtomicInteger afterGateRuns;
    /** Toda escrita de fim no registro, na ordem em que chegou: (status, erro). */
    private BlockingQueue<String> completions;
    /** O portão é decidido (recusado) de dentro do próprio passo — o aprovador automático. */
    private boolean autoReject;

    @BeforeEach
    void setUp() {
        flowRepository = new InMemoryFlowRepository();
        stateManager = new InMemoryStateManager();
        engine = FlowEngineFactory.create(flowRepository, null, null, stateManager);
        completions = new LinkedBlockingQueue<>();
        autoReject = false;
        store = new InMemoryWorkflowRuntimeStore() {
            @Override
            public void completeExecution(String id, String status, String error) {
                super.completeExecution(id, status, error);
                completions.add(status);
            }
        };
        queue = queueAt(Clock.systemUTC());
        afterGateRuns = new AtomicInteger();
        WorkflowDeserializer deserializer = json -> flowWithGate(String.valueOf(json.get("id")));
        crud = new SpringWorkflowCrudController(store, deserializer, engine, flowRepository,
                new AuditTrail(InMemoryAuditRepository::new), mock(McpAgentHost.class));
        agUi = new AgUiController(store, deserializer, engine, new EventStreamRegistry(),
                new DefaultAgUiEventMapper(), stateManager, flowRepository, new ObjectMapper());
    }

    private ApprovalQueueService queueAt(Clock clock) {
        return new ApprovalQueueService(stateManager, engine, Duration.ofMinutes(5), clock, null, store);
    }

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

    /** gate (APPROVAL) → remediar, com o id da execução — como o desserializador real devolve. */
    private Flow flowWithGate(String flowId) {
        HumanApprovalStep gate = new HumanApprovalStep("gate", List.of(edge("gate", "remediar")),
                Map.of("description", "Reiniciar o serviço traefik"), () -> engine);
        FlowStep first = autoReject ? rejectedFromInside(gate) : gate;
        return new SimpleFlow(flowId, new FlowMetadata("Remediação", "", "1.0.0", null, null, List.of()),
                List.of(first, countingStep("remediar")));
    }

    /**
     * O portão de verdade, recusado pela fila assim que o pedido existe e antes de o passo
     * devolver: a decisão chega com a travessia original ainda viva, e o callback de quem
     * disparou a execução só escreve depois da fila — a ordem que um cliente automático produz.
     */
    private FlowStep rejectedFromInside(HumanApprovalStep gate) {
        return new FlowStep() {
            @Override public String getId() { return gate.getId(); }
            @Override public StepType getType() { return gate.getType(); }
            @Override public List<StepConnection> getConnections() { return gate.getConnections(); }
            @Override public CompletableFuture<StepResult> execute(ExecutionContext c) {
                StepResult asked = gate.execute(c).join();
                String flowId = c.getState().getFlowId();
                queue.submitDecision(pendingRequestOf(flowId),
                        decision("REJECTED", "aprovador.automatico", "risco acima do limite"));
                return CompletableFuture.completedFuture(asked);
            }
        };
    }

    /** Espera {@code n} escritas de fim no registro — a da fila e a de quem disparou. */
    private List<String> awaitCompletions(int n) throws Exception {
        List<String> seen = new java.util.ArrayList<>();
        while (seen.size() < n) {
            String status = completions.poll(10, TimeUnit.SECONDS);
            assertThat(status).as("escrita de fim nº %d no registro", seen.size() + 1).isNotNull();
            seen.add(status);
        }
        return seen;
    }

    private String newWorkflow() {
        var created = crud.create(new HashMap<>(Map.of("metadata", Map.of("name", "Remediação"))));
        return String.valueOf(created.getBody().get("id"));
    }

    /** POST /api/workflows/{id}/execute → id da execução, já suspensa no portão. */
    private String executeUntilPaused() throws Exception {
        String workflowId = newWorkflow();
        var body = crud.execute(workflowId, Map.of("input", "traefik caiu")).getBody();
        String executionId = String.valueOf(body.get("executionId"));
        awaitRecord(executionId, "PAUSED");
        return executionId;
    }

    /** POST /ag-ui/workflows/{id} (o "Executar" do editor) → id da execução, suspensa no portão. */
    private String runFromEditorUntilPaused() throws Exception {
        String workflowId = newWorkflow();
        agUi.run(workflowId, new RunAgentInput(
                List.of(Map.of("role", "user", "content", "traefik caiu")), null, null, null, null, null));
        String executionId = String.valueOf(store.executions(DefaultTenant.ID, workflowId, 1).get(0).get("id"));
        awaitRecord(executionId, "PAUSED");
        return executionId;
    }

    private String pendingRequestOf(String executionId) {
        return queue.listPending("all").stream()
                .filter(p -> executionId.equals(p.flowId()))
                .map(ApprovalResponse::requestId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("sem pedido na fila para " + executionId));
    }

    private static ApprovalSubmitRequest decision(String decision, String who, String comment) {
        return new ApprovalSubmitRequest("all", decision, null, who, comment);
    }

    private Map<String, Object> awaitRecord(String executionId, String status) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        Map<String, Object> record = store.getExecution(DefaultTenant.ID, executionId);
        while (!status.equals(String.valueOf(record.get("status")))
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
            record = store.getExecution(DefaultTenant.ID, executionId);
        }
        assertThat(record.get("status")).as("status do registro de %s", executionId).isEqualTo(status);
        return record;
    }

    @Nested
    @DisplayName("execução por POST /execute")
    class RestExecute {

        @Test
        @DisplayName("aprovar: o registro acompanha a retomada até COMPLETED, com completedAt")
        void approvalFollowsTheResumeToCompleted() throws Exception {
            String executionId = executeUntilPaused();
            assertThat(afterGateRuns).hasValue(0);

            queue.submitDecision(pendingRequestOf(executionId), decision("APPROVED", "diretor", "ok"));

            Map<String, Object> record = awaitRecord(executionId, "COMPLETED");
            assertThat(record.get("completedAt")).isNotNull();
            assertThat(record.get("error")).isNull();
            assertThat(afterGateRuns).hasValue(1);
            assertThat(stateManager.loadState(executionId).getStatus()).isEqualTo(FlowStatus.COMPLETED);
        }

        @Test
        @DisplayName("recusar: o registro termina STOPPED com quem recusou e o comentário")
        void rejectionStopsTheRecordWithTheReason() throws Exception {
            String executionId = executeUntilPaused();

            queue.submitDecision(pendingRequestOf(executionId),
                    decision("REJECTED", "diretor.financeiro", "desconto alto demais"));

            Map<String, Object> record = awaitRecord(executionId, "STOPPED");
            assertThat(record.get("completedAt")).isNotNull();
            assertThat(String.valueOf(record.get("error")))
                    .contains("Recusado na aprovação", "gate", "diretor.financeiro", "desconto alto demais")
                    // RCHFLW-188 (18): a frase é a da UI (pt-BR), não a do inglês com o id cru.
                    .doesNotContain("Rejected at");
            assertThat(afterGateRuns).hasValue(0);
            assertThat(stateManager.loadState(executionId).getStatus()).isEqualTo(FlowStatus.STOPPED);
        }

        @Test
        @DisplayName("prazo vencido: o sweeper recusa e o registro termina STOPPED pelo timeout")
        void timeoutStopsTheRecord() throws Exception {
            String executionId = executeUntilPaused();

            int expired = queueAt(Clock.fixed(Instant.now().plus(Duration.ofHours(1)), ZoneOffset.UTC))
                    .expireStale();

            assertThat(expired).isEqualTo(1);
            Map<String, Object> record = awaitRecord(executionId, "STOPPED");
            assertThat(record.get("completedAt")).isNotNull();
            assertThat(String.valueOf(record.get("error"))).contains("prazo da aprovação").doesNotContain("PT");
            assertThat(afterGateRuns).hasValue(0);
        }
    }

    @Nested
    @DisplayName("execução pelo editor (AG-UI)")
    class EditorRun {

        @Test
        @DisplayName("aprovar retoma o fluxo e o registro chega a COMPLETED")
        void approvalResumesAnEditorRun() throws Exception {
            String executionId = runFromEditorUntilPaused();
            assertThat(afterGateRuns).hasValue(0);

            queue.submitDecision(pendingRequestOf(executionId), decision("APPROVED", "diretor", "ok"));

            awaitRecord(executionId, "COMPLETED");
            assertThat(afterGateRuns).as("o passo depois do portão rodou").hasValue(1);
            FlowState state = stateManager.loadState(executionId);
            assertThat(state.getStatus()).isEqualTo(FlowStatus.COMPLETED);
            assertThat(state.getVariables()).containsEntry(ExecutionKeys.APPROVAL_DECIDED_BY, "diretor");
        }

        @Test
        @DisplayName("recusar encerra o registro da execução do editor")
        void rejectionStopsAnEditorRun() throws Exception {
            String executionId = runFromEditorUntilPaused();

            queue.submitDecision(pendingRequestOf(executionId), decision("REJECTED", "diretor", "não"));

            awaitRecord(executionId, "STOPPED");
            assertThat(afterGateRuns).hasValue(0);
        }
    }

    @Nested
    @DisplayName("decisão antes de a travessia original desenrolar")
    class DecisionBeforeTheOriginalCallback {

        /**
         * A fila grava STOPPED com o motivo; depois o /execute grava o CANCELLED que a travessia
         * viva viu (a recusa parou a travessia). Sem a guarda do store, o registro terminava
         * CANCELLED, sem motivo, com o FlowState STOPPED.
         */
        @Test
        @DisplayName("/execute: o CANCELLED tardio de quem disparou não apaga o STOPPED da recusa")
        void lateCallbackDoesNotOverwriteTheRejection() throws Exception {
            autoReject = true;
            String executionId = String.valueOf(
                    crud.execute(newWorkflow(), Map.of("input", "traefik caiu")).getBody().get("executionId"));

            assertThat(awaitCompletions(2)).as("a fila escreve primeiro, quem disparou depois")
                    .containsExactly("STOPPED", "CANCELLED");

            assertLeftStoppedWithReason(executionId);
        }

        @Test
        @DisplayName("editor (AG-UI): o status tardio do RUN_FINISHED não apaga o STOPPED da recusa")
        void lateEditorCallbackDoesNotOverwriteTheRejection() throws Exception {
            autoReject = true;
            String workflowId = newWorkflow();
            agUi.run(workflowId, new RunAgentInput(
                    List.of(Map.of("role", "user", "content", "traefik caiu")), null, null, null, null, null));

            assertThat(awaitCompletions(2)).containsExactly("STOPPED", "CANCELLED");

            assertLeftStoppedWithReason(String.valueOf(store.executions(DefaultTenant.ID, workflowId, 1).get(0).get("id")));
        }

        private void assertLeftStoppedWithReason(String executionId) {
            Map<String, Object> record = store.getExecution(DefaultTenant.ID, executionId);
            assertThat(record.get("status")).as("registro igual ao estado durável").isEqualTo("STOPPED");
            assertThat(String.valueOf(record.get("error")))
                    .contains("Recusado na aprovação", "aprovador.automatico", "risco acima do limite");
            assertThat(record.get("completedAt")).isNotNull();
            assertThat(stateManager.loadState(executionId).getStatus()).isEqualTo(FlowStatus.STOPPED);
            assertThat(afterGateRuns).hasValue(0);
        }
    }

    /**
     * Decidido, o pedido sai da fila e {@code GET /api/approvals/{id}} respondia 404: quem abria o
     * link depois não via quem decidiu, embora o histórico do estado durável tivesse (RCHFLW-38).
     * Com a execução no link, o detalhe vem desse histórico.
     */
    @Nested
    @DisplayName("detalhe de um pedido já decidido")
    class DecidedDetail {

        @Test
        @DisplayName("recusado: com a execução, o detalhe diz quem recusou, quando e por quê")
        void rejectedDetailShowsTheDecider() throws Exception {
            String executionId = executeUntilPaused();
            String requestId = pendingRequestOf(executionId);
            queue.submitDecision(requestId, decision("REJECTED", "diretor.financeiro", "desconto alto demais"));
            awaitRecord(executionId, "STOPPED");

            ApprovalResponse detail = queue.getDetail(requestId, executionId);

            assertThat(detail.status()).isEqualTo("REJECTED");
            assertThat(detail.flowId()).isEqualTo(executionId);
            assertThat(detail.stepId()).isEqualTo("gate");
            assertThat(detail.decidedBy()).isEqualTo("diretor.financeiro");
            assertThat(detail.comment()).isEqualTo("desconto alto demais");
            assertThat(detail.decidedAt()).isNotNull();
            // Sem a execução não há busca entre todos os estados terminais: continua 404.
            assertThatThrownBy(() -> queue.getDetail(requestId))
                    .isInstanceOf(java.util.NoSuchElementException.class);
        }

        @Test
        @DisplayName("aprovado e concluído: o detalhe diz quem aprovou")
        void approvedDetailShowsTheDecider() throws Exception {
            String executionId = executeUntilPaused();
            String requestId = pendingRequestOf(executionId);
            queue.submitDecision(requestId, decision("APPROVED", "diretor", "ok"));
            awaitRecord(executionId, "COMPLETED");

            ApprovalResponse detail = queue.getDetail(requestId, executionId);

            assertThat(detail.status()).isEqualTo("APPROVED");
            assertThat(detail.decidedBy()).isEqualTo("diretor");
            assertThat(detail.comment()).isEqualTo("ok");
        }

        @Test
        @DisplayName("vencido: o detalhe mostra EXPIRED, decidido pelo prazo")
        void expiredDetailShowsTheTimeout() throws Exception {
            String executionId = executeUntilPaused();
            String requestId = pendingRequestOf(executionId);
            queueAt(Clock.fixed(Instant.now().plus(Duration.ofHours(1)), ZoneOffset.UTC)).expireStale();
            awaitRecord(executionId, "STOPPED");

            ApprovalResponse detail = queue.getDetail(requestId, executionId);

            assertThat(detail.status()).isEqualTo("EXPIRED");
            assertThat(detail.decidedBy()).isEqualTo("system:timeout");
        }

        @Test
        @DisplayName("id que não é desta execução continua 404")
        void requestOfAnotherExecutionIsNotFound() throws Exception {
            String executionId = executeUntilPaused();
            String requestId = pendingRequestOf(executionId);
            queue.submitDecision(requestId, decision("REJECTED", "diretor", "não"));
            awaitRecord(executionId, "STOPPED");

            assertThatThrownBy(() -> queue.getDetail("outro-pedido", executionId))
                    .isInstanceOf(java.util.NoSuchElementException.class);
            assertThatThrownBy(() -> queue.getDetail(requestId, "outra-execucao"))
                    .isInstanceOf(java.util.NoSuchElementException.class);
        }
    }

    @Nested
    @DisplayName("retomada que falha não é sucesso")
    class FailedResume {

        @Test
        @DisplayName("fluxo fora do FlowRepository: a fila recusa e o pedido continua decidível")
        void unregisteredFlowIsRefusedAndStaysPending() throws Exception {
            // Execução como o editor fazia antes: registro criado, fluxo nunca registrado.
            var record = store.createExecution(DefaultTenant.ID, "wf-x", "Remediação");
            String executionId = String.valueOf(record.get("id"));
            ExecutionContext ctx = new DefaultExecutionContext(null, "ag-ui", executionId,
                    MessageWindowChatMemory.builder().maxMessages(5).build());
            assertThat(engine.execute(flowWithGate(executionId), ctx).get(10, TimeUnit.SECONDS).getStatus())
                    .isEqualTo(ExecutionStatus.PAUSED);
            store.completeExecution(executionId, "PAUSED", null);
            String requestId = pendingRequestOf(executionId);

            assertThatThrownBy(() -> queue.submitDecision(requestId, decision("APPROVED", "diretor", "ok")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not registered");

            assertThat(pendingRequestOf(executionId))
                    .as("o pedido não foi consumido: ainda está na fila").isEqualTo(requestId);
            assertThat(store.getExecution(DefaultTenant.ID, executionId).get("status")).isEqualTo("PAUSED");
            assertThat(afterGateRuns).hasValue(0);
        }

        @Test
        @DisplayName("retomada que falha depois de aceita: o registro vira FAILED com o erro")
        void asyncResumeFailureMarksTheRecordFailed() throws Exception {
            String executionId = executeUntilPaused();
            String requestId = pendingRequestOf(executionId);
            FlowEngine failing = mock(FlowEngine.class);
            when(failing.submitApproval(eq(executionId), eq(requestId), anyBoolean(), any(),
                    anyString(), any()))
                    .thenReturn(CompletableFuture.failedFuture(
                            new FlowEngineException("memória indisponível")));
            ApprovalQueueService failingQueue = new ApprovalQueueService(
                    stateManager, failing, Duration.ZERO, Clock.systemUTC(), null, store);

            failingQueue.submitDecision(requestId, decision("APPROVED", "diretor", "ok"));

            Map<String, Object> failed = awaitRecord(executionId, "FAILED");
            assertThat(failed.get("error")).isEqualTo("memória indisponível");
            assertThat(failed.get("completedAt")).isNotNull();
        }
    }

    /**
     * QA de aprovações (RCHFLW-126/127/129): a duração das recusadas, o pedido vencido antes da
     * varredura e o nome do fluxo na fila.
     */
    @Nested
    @DisplayName("QA de aprovações: duração, vencimento e nome do fluxo")
    class QaAprovacoes {

        private long durationOf(Map<String, Object> record) {
            assertThat(record.get("duration")).as("duração gravada no registro").isInstanceOf(Number.class);
            return ((Number) record.get("duration")).longValue();
        }

        @Test
        @DisplayName("recusada: a duração é do início ao fim da execução, não só do trecho até o portão")
        void rejectedDurationCoversTheWait() throws Exception {
            String executionId = executeUntilPaused();
            Thread.sleep(400);

            queue.submitDecision(pendingRequestOf(executionId), decision("REJECTED", "diretor", "não"));

            Map<String, Object> record = awaitRecord(executionId, "STOPPED");
            // Antes: ficava a duração do trecho até o portão (19 ms para 5 min de espera)
            assertThat(durationOf(record)).isGreaterThanOrEqualTo(400);
        }

        @Test
        @DisplayName("expirada: a duração também conta a espera")
        void expiredDurationCoversTheWait() throws Exception {
            String executionId = executeUntilPaused();
            Thread.sleep(400);

            queueAt(Clock.fixed(Instant.now().plus(Duration.ofHours(1)), ZoneOffset.UTC)).expireStale();

            assertThat(durationOf(awaitRecord(executionId, "STOPPED"))).isGreaterThanOrEqualTo(400);
        }

        @Test
        @DisplayName("vencida e ainda não varrida: fila, detalhe e execução já dizem expirada, sem decisão nova")
        void expiredBeforeTheSweepIsCoherent() throws Exception {
            String executionId = executeUntilPaused();
            String requestId = pendingRequestOf(executionId);
            ApprovalQueueService depoisDoPrazo =
                    queueAt(Clock.fixed(Instant.now().plus(Duration.ofHours(1)), ZoneOffset.UTC));

            assertThat(depoisDoPrazo.listPending("all")).isEmpty();
            // Antes: o detalhe (GET /api/approvals/{id}) ainda respondia PENDING até o sweeper passar
            ApprovalResponse detail = depoisDoPrazo.getDetail(requestId, executionId);
            assertThat(detail.status()).isEqualTo("EXPIRED");
            assertThat(detail.decidedBy()).as("ler não decide: quem recusa é o sweeper").isNull();
            FlowState state = stateManager.loadState(executionId);
            assertThat(depoisDoPrazo.isPendingExpired(state)).isTrue();
            assertThat(depoisDoPrazo.deadlineOf(state)).isNotNull();
            assertThat(state.getStatus()).isEqualTo(FlowStatus.AWAITING_APPROVAL);
            assertThat(state.getVariables()).doesNotContainKey(ExecutionKeys.APPROVAL_HISTORY);

            // A recusa tardia chega em português a quem tentou decidir
            assertThatThrownBy(() -> depoisDoPrazo.submitDecision(requestId, decision("APPROVED", "diretor", "ok")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("prazo desta aprovação venceu");
            // Pelo relógio de agora, o mesmo pedido continua pendente e no prazo
            assertThat(queue.isPendingExpired(state)).isFalse();
        }

        @Test
        @DisplayName("a fila traz o nome do fluxo, lido do registro da execução")
        void queueShowsTheFlowName() throws Exception {
            String executionId = executeUntilPaused();

            ApprovalResponse pending = queue.listPending("all").stream()
                    .filter(p -> executionId.equals(p.flowId())).findFirst().orElseThrow();

            // Antes: só o id da execução e do passo; o nome não vinha
            assertThat(pending.flowName()).isEqualTo("Remediação");
            assertThat(queue.getDetail(pending.requestId(), executionId).flowName()).isEqualTo("Remediação");
        }
    }
}
