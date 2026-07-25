package br.com.archflow.api.approval;

import br.com.archflow.api.approval.dto.ApprovalResponse;
import br.com.archflow.api.approval.dto.ApprovalSubmitRequest;
import br.com.archflow.api.approval.impl.ApprovalQueueService;
import br.com.archflow.api.flow.InMemoryStateManager;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.core.StateManager;
import br.com.archflow.model.engine.ExecutionKeys;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.flow.FlowStatus;
import br.com.archflow.model.engine.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A fila de aprovações agora é derivada do estado durável dos fluxos e decide
 * pelo motor. O teste anterior exercitava um {@code ApprovalRegistry} em memória
 * que nenhum produtor alimentava — verificava um mecanismo que não era alcançado
 * em produção.
 */
@DisplayName("ApprovalQueueService — fila derivada do estado durável")
class ApprovalQueueServiceTest {

    /** Motor falso: registra as submissões e simula o consumo do requestId. */
    private static final class RecordingEngine implements FlowEngine {
        record Submission(String flowId, String requestId, boolean approved, Object payload) {}

        final List<Submission> submissions = new ArrayList<>();
        private final StateManager stateManager;
        RuntimeException failWith;

        RecordingEngine(StateManager stateManager) {
            this.stateManager = stateManager;
        }

        @Override
        public CompletableFuture<FlowResult> submitApproval(String flowId, String requestId,
                                                            boolean approved, Object editedPayload) {
            if (failWith != null) {
                throw failWith;
            }
            submissions.add(new Submission(flowId, requestId, approved, editedPayload));
            // Espelha o motor real: consome o requestId e tira o fluxo de
            // AWAITING_APPROVAL, então ele deixa de ser encontrável na fila.
            FlowState state = stateManager.loadState(flowId);
            Map<String, Object> vars = new HashMap<>(state.getVariables());
            vars.put(ExecutionKeys.APPROVAL_REQUEST_ID, "");
            stateManager.saveState(flowId, FlowState.builder()
                    .tenantId(state.getTenantId())
                    .flowId(state.getFlowId())
                    .status(approved ? FlowStatus.RUNNING : FlowStatus.STOPPED)
                    .currentStepId(state.getCurrentStepId())
                    .variables(vars)
                    .build());
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletableFuture<FlowResult> startFlow(String f, Map<String, Object> i) {
            throw new UnsupportedOperationException();
        }
        @Override public CompletableFuture<FlowResult> execute(Flow f, ExecutionContext c) {
            throw new UnsupportedOperationException();
        }
        @Override public CompletableFuture<FlowResult> resumeFlow(String f, ExecutionContext c) {
            throw new UnsupportedOperationException();
        }
        @Override public FlowStatus getFlowStatus(String flowId) {
            throw new UnsupportedOperationException();
        }
        @Override public void pause(String flowId) { }
        @Override public void cancel(String flowId) { }
        @Override public Set<String> getActiveFlows() { return Set.of(); }
    }

    private StateManager stateManager;
    private RecordingEngine engine;
    private ApprovalQueueService service;

    @BeforeEach
    void setUp() {
        stateManager = new InMemoryStateManager();
        engine = new RecordingEngine(stateManager);
        service = new ApprovalQueueService(stateManager, engine);
    }

    /** Grava um fluxo suspenso como o {@code requestApproval} do motor faria. */
    private void awaitingApproval(String flowId, String tenant, String requestId,
                                  String stepId, Instant requestedAt) {
        Map<String, Object> vars = new HashMap<>();
        vars.put(ExecutionKeys.APPROVAL_REQUEST_ID, requestId);
        vars.put(ExecutionKeys.APPROVAL_PROPOSAL, Map.of("text", "Draft reply"));
        vars.put(ExecutionKeys.APPROVAL_STEP_ID, stepId);
        vars.put(ExecutionKeys.APPROVAL_DESCRIPTION, "Approve outgoing draft");
        vars.put(ExecutionKeys.APPROVAL_REQUESTED_AT, requestedAt.toString());
        stateManager.saveState(flowId, FlowState.builder()
                .tenantId(tenant)
                .flowId(flowId)
                .status(FlowStatus.AWAITING_APPROVAL)
                .currentStepId(stepId)
                .variables(vars)
                .build());
    }

    @Test
    @DisplayName("lista por tenant, mais antigas primeiro, com proposta e descrição")
    void listPending() {
        Instant t0 = Instant.parse("2026-07-01T10:00:00Z");
        awaitingApproval("flow-a", "acme", "req-1", "step-1", t0);
        awaitingApproval("flow-b", "acme", "req-2", "step-2", t0.plusSeconds(60));
        awaitingApproval("flow-c", "beta", "req-3", "step-3", t0.plusSeconds(120));

        List<ApprovalResponse> list = service.listPending("acme");

        assertThat(list).extracting(ApprovalResponse::requestId).containsExactly("req-1", "req-2");
        assertThat(list.get(0).stepId()).isEqualTo("step-1");
        assertThat(list.get(0).description()).isEqualTo("Approve outgoing draft");
        assertThat(list.get(0).createdAt()).isEqualTo(t0);
        assertThat(list.get(0).proposal())
                .as("a proposta era descartada pelo motor; agora chega ao decisor")
                .isEqualTo(Map.of("text", "Draft reply"));
    }

    @Test
    @DisplayName("tenant 'all' (e ausente) atravessa todos os tenants")
    void listPendingAcrossTenants() {
        Instant t0 = Instant.parse("2026-07-01T10:00:00Z");
        awaitingApproval("flow-a", "acme", "req-1", "step-1", t0);
        awaitingApproval("flow-c", "beta", "req-3", "step-3", t0.plusSeconds(10));

        assertThat(service.listPending("all")).hasSize(2);
        assertThat(service.listPending(null)).hasSize(2);
        assertThat(service.listPending("acme")).hasSize(1);
        assertThat(service.pendingCount("ghost")).isZero();
    }

    @Test
    @DisplayName("fluxo em AWAITING_APPROVAL sem requestId vivo não polui a fila")
    void consumedRequestIsNotPending() {
        Instant t0 = Instant.parse("2026-07-01T10:00:00Z");
        awaitingApproval("flow-a", "acme", "", "step-1", t0);

        assertThat(service.listPending("acme")).isEmpty();
    }

    @Test
    @DisplayName("getDetail encontra por requestId, sem precisar do tenant")
    void getDetail() {
        awaitingApproval("flow-a", "acme", "req-1", "step-1", Instant.now());

        ApprovalResponse detail = service.getDetail("req-1");

        assertThat(detail.requestId()).isEqualTo("req-1");
        assertThat(detail.status()).isEqualTo("PENDING");
        assertThat(detail.flowId()).isEqualTo("flow-a");
    }

    @Test
    @DisplayName("getDetail de id desconhecido levanta NoSuchElementException")
    void getDetailUnknown() {
        assertThatThrownBy(() -> service.getDetail("ghost"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("APPROVED chega ao motor como aprovação e a fila esvazia")
    void submitApproved() {
        awaitingApproval("flow-a", "acme", "req-1", "step-1", Instant.now());

        ApprovalResponse resp = service.submitDecision("req-1",
                new ApprovalSubmitRequest("acme", "APPROVED", null, "alice"));

        assertThat(resp.status()).isEqualTo("APPROVED");
        assertThat(engine.submissions).singleElement().satisfies(s -> {
            assertThat(s.flowId()).isEqualTo("flow-a");
            assertThat(s.requestId()).isEqualTo("req-1");
            assertThat(s.approved()).isTrue();
            assertThat(s.payload()).isNull();
        });
        assertThat(service.pendingCount("acme")).isZero();
    }

    @Test
    @DisplayName("REJECTED chega ao motor como recusa")
    void submitRejected() {
        awaitingApproval("flow-a", "acme", "req-1", "step-1", Instant.now());

        ApprovalResponse resp = service.submitDecision("req-1",
                new ApprovalSubmitRequest("acme", "rejected", null, "bob"));

        assertThat(resp.status()).isEqualTo("REJECTED");
        assertThat(engine.submissions).singleElement()
                .satisfies(s -> assertThat(s.approved()).isFalse());
    }

    @Test
    @DisplayName("EDITED encaminha o payload editado ao motor")
    void submitEdited() {
        awaitingApproval("flow-a", "acme", "req-1", "step-1", Instant.now());

        ApprovalResponse resp = service.submitDecision("req-1",
                new ApprovalSubmitRequest("acme", "EDITED", Map.of("text", "Edited draft"), "carol"));

        assertThat(resp.status()).isEqualTo("EDITED");
        assertThat(engine.submissions).singleElement().satisfies(s -> {
            assertThat(s.approved()).isTrue();
            assertThat(s.payload()).isEqualTo(Map.of("text", "Edited draft"));
        });
    }

    @Test
    @DisplayName("decisão desconhecida é rejeitada antes de tocar o motor")
    void submitUnknownDecision() {
        awaitingApproval("flow-a", "acme", "req-1", "step-1", Instant.now());

        assertThatThrownBy(() -> service.submitDecision("req-1",
                new ApprovalSubmitRequest("acme", "MAYBE", null, "x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(engine.submissions).isEmpty();
    }

    @Test
    @DisplayName("submeter id desconhecido levanta NoSuchElementException")
    void submitUnknownRequest() {
        assertThatThrownBy(() -> service.submitDecision("ghost",
                new ApprovalSubmitRequest("acme", "APPROVED", null, "x")))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("submeter duas vezes: a segunda não encontra mais o pendente")
    void submitTwice() {
        awaitingApproval("flow-a", "acme", "req-1", "step-1", Instant.now());
        service.submitDecision("req-1", new ApprovalSubmitRequest("acme", "APPROVED", null, "x"));

        assertThatThrownBy(() -> service.submitDecision("req-1",
                new ApprovalSubmitRequest("acme", "REJECTED", null, "x")))
                .isInstanceOf(NoSuchElementException.class);
        assertThat(engine.submissions).hasSize(1);
    }

    @Test
    @DisplayName("recusa do motor (corrida) vira IllegalStateException")
    void engineRejectionSurfaces() {
        awaitingApproval("flow-a", "acme", "req-1", "step-1", Instant.now());
        engine.failWith = new RuntimeException("Unknown approval requestId for flow flow-a");

        assertThatThrownBy(() -> service.submitDecision("req-1",
                new ApprovalSubmitRequest("acme", "APPROVED", null, "x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("req-1");
    }

    @Test
    @DisplayName("construtor exige stateManager e flowEngine")
    void constructorRequiresCollaborators() {
        assertThatThrownBy(() -> new ApprovalQueueService(null, engine))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ApprovalQueueService(stateManager, null))
                .isInstanceOf(NullPointerException.class);
    }
}
