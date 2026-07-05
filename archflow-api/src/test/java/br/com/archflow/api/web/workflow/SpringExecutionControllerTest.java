package br.com.archflow.api.web.workflow;

import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.core.StateManager;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.flow.FlowStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
        controller = new SpringExecutionController(store, engine, stateManager);
    }

    @Test
    void cancelsARunningExecution() {
        when(store.getExecution("e1")).thenReturn(new LinkedHashMap<>(Map.of("id", "e1", "status", "RUNNING")));

        var response = controller.cancel("e1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(engine).cancel("e1");
        verify(store).completeExecution("e1", "CANCELLED", null);
    }

    @Test
    void cancelReturns404WhenUnknown() {
        when(store.getExecution("nope")).thenReturn(null);

        assertThat(controller.cancel("nope").getStatusCode().value()).isEqualTo(404);
        verifyNoInteractions(engine);
    }

    @Test
    void resumeReturns409WhenNoSavedState() {
        when(store.getExecution("e1")).thenReturn(new LinkedHashMap<>(Map.of("id", "e1", "status", "FAILED")));
        when(stateManager.loadState("e1")).thenReturn(null);

        assertThat(controller.resume("e1").getStatusCode().value()).isEqualTo(409);
        verifyNoInteractions(engine);
    }

    @Test
    void resumeReturns409WhenFinalState() {
        when(store.getExecution("e1")).thenReturn(new LinkedHashMap<>(Map.of("id", "e1", "status", "COMPLETED")));
        when(stateManager.loadState("e1")).thenReturn(FlowState.builder().status(FlowStatus.COMPLETED).build());

        assertThat(controller.resume("e1").getStatusCode().value()).isEqualTo(409);
        verifyNoInteractions(engine);
    }

    @Test
    void resumeSubmitsWhenPaused() {
        // The controller re-reads the record after store.markResumed flips it
        // to RUNNING, so the mocked store answers PAUSED first, RUNNING after.
        when(store.getExecution("e1"))
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
        var realController = new SpringExecutionController(realStore, engine, stateManager);
        String id = realStore.createExecution("wf", "W").get("id").toString();
        realStore.completeExecution(id, "PAUSED", null); // non-terminal, resumable
        when(stateManager.loadState(id)).thenReturn(FlowState.builder().status(FlowStatus.PAUSED).build());
        when(engine.resumeFlow(eq(id), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("boom")));

        realController.resume(id);

        assertThat(realStore.getExecution(id).get("status")).isEqualTo("FAILED");
        assertThat(realStore.getExecution(id).get("error")).isEqualTo("boom");
    }
}
