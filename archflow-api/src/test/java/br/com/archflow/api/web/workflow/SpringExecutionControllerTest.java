package br.com.archflow.api.web.workflow;

import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.core.StateManager;
import br.com.archflow.engine.exceptions.FlowNotFoundException;
import br.com.archflow.model.engine.ExecutionKeys;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.flow.FlowStatus;
import br.com.archflow.model.tenant.DefaultTenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SpringExecutionControllerTest {

    private InMemoryWorkflowRuntimeStore store;
    private FlowEngine engine;
    private StateManager stateManager;
    private SpringExecutionController controller;

    @BeforeEach
    void setup() {
        store = mock(InMemoryWorkflowRuntimeStore.class);
        engine = mock(FlowEngine.class);
        stateManager = mock(StateManager.class);
        controller = new SpringExecutionController(store, engine, stateManager, null);
    }

    @Test
    void cancelsARunningExecution() {
        when(store.getExecution(DefaultTenant.ID, "e1")).thenReturn(new LinkedHashMap<>(Map.of("id", "e1", "status", "RUNNING")));

        var response = controller.cancel("e1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(engine).cancel("e1");
        verify(store).cancelExecution("e1");
    }

    @Test
    void cancelWithoutALiveTraversalDoesNotFreezeTheRecord() {
        // O motor não tinha a travessia (desenrolou num portão, ou terminou no meio): CANCELLED sem a marca, para o
        // PAUSED e o desfecho da decisão ainda poderem chegar.
        when(store.getExecution(DefaultTenant.ID, "e1"))
                .thenReturn(new LinkedHashMap<>(Map.of("id", "e1", "status", "RUNNING")));
        doThrow(new FlowNotFoundException("e1")).when(engine).cancel("e1");

        assertThat(controller.cancel("e1").getStatusCode().value()).isEqualTo(200);
        verify(store).completeExecution("e1", "CANCELLED", null);
        verify(store, never()).cancelExecution("e1");
    }

    @Test
    void cancelReturns404WhenUnknown() {
        when(store.getExecution(DefaultTenant.ID, "nope")).thenReturn(null);

        assertThat(controller.cancel("nope").getStatusCode().value()).isEqualTo(404);
        verifyNoInteractions(engine);
    }

    @Test
    void resumeReturns409WhenNoSavedState() {
        when(store.getExecution(DefaultTenant.ID, "e1")).thenReturn(new LinkedHashMap<>(Map.of("id", "e1", "status", "FAILED")));
        when(stateManager.loadState("e1")).thenReturn(null);

        assertThat(controller.resume("e1").getStatusCode().value()).isEqualTo(409);
        verifyNoInteractions(engine);
    }

    @Test
    void resumeReturns409WhenFinalState() {
        when(store.getExecution(DefaultTenant.ID, "e1")).thenReturn(new LinkedHashMap<>(Map.of("id", "e1", "status", "COMPLETED")));
        when(stateManager.loadState("e1")).thenReturn(FlowState.builder().status(FlowStatus.COMPLETED).build());

        assertThat(controller.resume("e1").getStatusCode().value()).isEqualTo(409);
        verifyNoInteractions(engine);
    }

    /**
     * "Quem autorizou isto" sai do estado durável, não do registro: as decisões já tomadas (histórico
     * do motor) e o pedido ainda aberto vão junto do detalhe da execução (RCHFLW-38).
     */
    @Test
    void getExposesWhoDecidedAndThePendingRequestFromTheDurableState() {
        when(store.getExecution(DefaultTenant.ID, "e1")).thenReturn(new LinkedHashMap<>(Map.of("id", "e1", "status", "PAUSED")));
        var history = java.util.List.of(Map.of("stepId", "gate-a", "requestId", "req-a", "decision", "APPROVED",
                "decidedBy", "maria", "comment", "ok", "decidedAt", "2026-11-24T12:00:00Z"));
        when(stateManager.loadState("e1")).thenReturn(FlowState.builder()
                .status(FlowStatus.AWAITING_APPROVAL)
                .variables(new java.util.HashMap<>(Map.of(
                        ExecutionKeys.APPROVAL_HISTORY, history,
                        ExecutionKeys.APPROVAL_REQUEST_ID, "req-b",
                        ExecutionKeys.APPROVAL_STEP_ID, "gate-b")))
                .build());

        Map<String, Object> body = controller.get("e1").getBody();

        assertThat(body).containsEntry("approvals", history);
        assertThat(body).containsEntry("pendingApproval", Map.of("requestId", "req-b", "stepId", "gate-b"));
    }

    /**
     * Vencido e ainda não varrido, o pedido já saiu da fila; o detalhe da execução dizia "aguardando
     * decisão" e mandava decidir lá (RCHFLW-126). Com a fila ligada, ele diz o prazo e que venceu.
     */
    @Test
    void getMarksThePendingRequestExpiredByTheQueueRule() {
        when(store.getExecution(DefaultTenant.ID, "e1"))
                .thenReturn(new LinkedHashMap<>(Map.of("id", "e1", "status", "PAUSED")));
        java.time.Instant requested = java.time.Instant.parse("2026-11-24T12:00:00Z");
        FlowState state = FlowState.builder()
                .status(FlowStatus.AWAITING_APPROVAL)
                .variables(new java.util.HashMap<>(Map.of(
                        ExecutionKeys.APPROVAL_REQUEST_ID, "req-b",
                        ExecutionKeys.APPROVAL_STEP_ID, "gate-b",
                        ExecutionKeys.APPROVAL_REQUESTED_AT, requested.toString())))
                .build();
        when(stateManager.loadState("e1")).thenReturn(state);
        controller.setApprovalQueue(new br.com.archflow.api.approval.impl.ApprovalQueueService(stateManager, engine,
                java.time.Duration.ofMinutes(5),
                java.time.Clock.fixed(requested.plus(java.time.Duration.ofMinutes(6)), java.time.ZoneOffset.UTC)));

        Map<String, Object> body = controller.get("e1").getBody();

        assertThat(body).containsEntry("pendingApproval", Map.of("requestId", "req-b", "stepId", "gate-b",
                "expiresAt", "2026-11-24T12:05:00Z", "expired", true));
    }

    @Test
    void getOmitsApprovalFieldsWhenThereAreNone() {
        when(store.getExecution(DefaultTenant.ID, "e1")).thenReturn(new LinkedHashMap<>(Map.of("id", "e1", "status", "COMPLETED")));
        when(stateManager.loadState("e1")).thenReturn(FlowState.builder()
                .status(FlowStatus.COMPLETED).variables(new java.util.HashMap<>()).build());

        assertThat(controller.get("e1").getBody()).doesNotContainKeys("approvals", "pendingApproval");
    }

    /** Retomar um portão à espera concluía o fluxo sem decisão e sem decisor. */
    @Test
    void resumeReturns409WhenAwaitingAHumanDecision() {
        when(store.getExecution(DefaultTenant.ID, "e1")).thenReturn(new LinkedHashMap<>(Map.of("id", "e1", "status", "PAUSED")));
        when(stateManager.loadState("e1")).thenReturn(FlowState.builder()
                .status(FlowStatus.AWAITING_APPROVAL)
                .variables(new java.util.HashMap<>(Map.of(ExecutionKeys.APPROVAL_REQUEST_ID, "req-1")))
                .build());

        var response = controller.resume("e1");

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(String.valueOf(response.getBody().get("error"))).contains("awaiting a human decision");
        verifyNoInteractions(engine);
        verify(store, org.mockito.Mockito.never()).markResumed("e1");
    }

    @Test
    void resumeSubmitsWhenPaused() {
        // The controller re-reads the record after store.markResumed flips it
        // to RUNNING, so the mocked store answers PAUSED first, RUNNING after.
        when(store.getExecution(DefaultTenant.ID, "e1"))
                .thenReturn(new LinkedHashMap<>(Map.of("id", "e1", "status", "PAUSED")))
                .thenReturn(new LinkedHashMap<>(Map.of("id", "e1", "status", "RUNNING")));
        when(stateManager.loadState("e1")).thenReturn(FlowState.builder().status(FlowStatus.PAUSED).build());
        // Never-completing future so whenComplete doesn't overwrite the RUNNING marker.
        when(engine.resumeFlow(eq("e1"), any())).thenReturn(new CompletableFuture<FlowResult>());

        var response = controller.resume("e1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("status")).isEqualTo("RUNNING");
        verify(store).markResumed("e1");
        verify(engine).resumeFlow(eq("e1"), any());
    }

    @Test
    void resumeKeepsTerminalResultWhenResumeFailsSynchronously() {
        // Guards the markResumed-BEFORE-whenComplete ordering. A real store plus a
        // synchronously-completed failure makes whenComplete run inline: with the
        // correct order the record ends FAILED; if markResumed were wired AFTER the
        // callback it would clobber the failure back to RUNNING (the stuck-run bug).
        var realStore = new InMemoryWorkflowRuntimeStore();
        var realController = new SpringExecutionController(realStore, engine, stateManager, null);
        String id = realStore.createExecution(DefaultTenant.ID, "wf", "W").get("id").toString();
        realStore.completeExecution(id, "PAUSED", null); // non-terminal, resumable
        when(stateManager.loadState(id)).thenReturn(FlowState.builder().status(FlowStatus.PAUSED).build());
        when(engine.resumeFlow(eq(id), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("boom")));

        realController.resume(id);

        assertThat(realStore.getExecution(DefaultTenant.ID, id).get("status")).isEqualTo("FAILED");
        assertThat(realStore.getExecution(DefaultTenant.ID, id).get("error")).isEqualTo("boom");
    }

    @Test
    void listOmitsStepOutputsButDetailKeepsThem() {
        // A listagem é sondada pela UI; a saída (até 4 KB por passo) só serve ao detalhe.
        Map<String, Object> stepWithOutput = new LinkedHashMap<>(Map.of(
                "stepId", "node-a", "status", "COMPLETED", "output", "{\"x\":1}"));
        Map<String, Object> stepWithout = new LinkedHashMap<>(Map.of("stepId", "node-b", "status", "COMPLETED"));
        Map<String, Object> execution = new LinkedHashMap<>(Map.of(
                "id", "e1", "status", "COMPLETED",
                "steps", new java.util.ArrayList<>(java.util.List.of(stepWithOutput, stepWithout))));
        when(store.executions(DefaultTenant.ID, null, null)).thenReturn(java.util.List.of(execution));
        when(store.getExecution(DefaultTenant.ID, "e1")).thenReturn(execution);

        var listed = controller.list(null, null);

        @SuppressWarnings("unchecked")
        var listedSteps = (java.util.List<Map<String, Object>>) listed.get(0).get("steps");
        assertThat(listedSteps).hasSize(2);
        assertThat(listedSteps).allSatisfy(s -> assertThat(s).doesNotContainKey("output"));
        assertThat(listedSteps.get(0)).containsEntry("stepId", "node-a").containsEntry("status", "COMPLETED");
        // Copia, não muta: o registro do store (e o detalhe) continua com a saída.
        assertThat(stepWithOutput).containsEntry("output", "{\"x\":1}");
        @SuppressWarnings("unchecked")
        var detailSteps = (java.util.List<Map<String, Object>>) controller.get("e1").getBody().get("steps");
        assertThat(detailSteps.get(0)).containsEntry("output", "{\"x\":1}");
    }

    @Test
    void listReturnsRecordsWithoutStepsUnchanged() {
        Map<String, Object> execution = new LinkedHashMap<>(Map.of("id", "e1", "status", "RUNNING"));
        when(store.executions(DefaultTenant.ID, "wf", 5)).thenReturn(java.util.List.of(execution));

        assertThat(controller.list("wf", 5)).containsExactly(execution);
    }
}
