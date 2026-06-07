package br.com.archflow.api.web.workflow;

import br.com.archflow.engine.api.FlowEngine;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SpringExecutionControllerTest {

    @Test
    void cancelsARunningExecution() {
        var store = mock(InMemoryWorkflowRuntimeStore.class);
        var engine = mock(FlowEngine.class);
        when(store.getExecution("e1")).thenReturn(new LinkedHashMap<>(Map.of("id", "e1", "status", "RUNNING")));

        var controller = new SpringExecutionController(store, engine);
        var response = controller.cancel("e1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(engine).cancel("e1");
        verify(store).completeExecution("e1", "CANCELLED", null);
    }

    @Test
    void returns404WhenExecutionUnknown() {
        var store = mock(InMemoryWorkflowRuntimeStore.class);
        var engine = mock(FlowEngine.class);
        when(store.getExecution("nope")).thenReturn(null);

        var controller = new SpringExecutionController(store, engine);

        assertThat(controller.cancel("nope").getStatusCode().value()).isEqualTo(404);
        verifyNoInteractions(engine);
    }
}
