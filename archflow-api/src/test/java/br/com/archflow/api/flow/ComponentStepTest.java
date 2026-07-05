package br.com.archflow.api.flow;

import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.StepStatus;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.model.flow.StepType;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ComponentStepTest {

    private ComponentCatalog catalog;
    private ExecutionContext ctx;

    @BeforeEach
    void setup() {
        catalog = mock(ComponentCatalog.class);
        ctx = mock(ExecutionContext.class);
        when(ctx.get(anyString())).thenReturn(Optional.empty());
    }

    private ComponentStep step(String componentId) {
        return new ComponentStep("s1", StepType.TOOL, componentId, "execute", List.of(), catalog);
    }

    @Test
    void invokesComponentAndThreadsOutputIntoContext() throws Exception {
        AIComponent component = mock(AIComponent.class);
        when(catalog.getComponent("c1")).thenReturn(Optional.of(component));
        when(component.execute(any(), any(), any())).thenReturn("out");

        StepResult result = step("c1").execute(ctx).join();

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).contains("out");
        verify(ctx).set("s1", "out");
        verify(ctx).set("input", "out");
    }

    @Test
    void failsWhenComponentNotFound() {
        when(catalog.getComponent("missing")).thenReturn(Optional.empty());

        StepResult result = step("missing").execute(ctx).join();

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("component not found: missing");
    }

    @Test
    void turnsComponentExceptionIntoFailedResult() throws Exception {
        AIComponent component = mock(AIComponent.class);
        when(catalog.getComponent("c1")).thenReturn(Optional.of(component));
        when(component.execute(any(), any(), any())).thenThrow(new RuntimeException("boom"));

        StepResult result = step("c1").execute(ctx).join();

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("boom");
    }
}
