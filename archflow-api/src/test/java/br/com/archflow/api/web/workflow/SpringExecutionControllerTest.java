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
        when(store.getExecution("e1")).thenReturn(new LinkedHashMap<>(Map.of("id", "e1", "status", "PAUSED")));
        when(stateManager.loadState("e1")).thenReturn(FlowState.builder().status(FlowStatus.PAUSED).build());
        // Never-completing future so whenComplete doesn't overwrite the RUNNING marker.
        when(engine.resumeFlow(eq("e1"), any())).thenReturn(new CompletableFuture<FlowResult>());

        var response = controller.resume("e1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("status")).isEqualTo("RUNNING");
        verify(engine).resumeFlow(eq("e1"), any());
    }
}
