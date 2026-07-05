package br.com.archflow.api.flow;

import br.com.archflow.agent.orchestration.SupervisorResult;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.engine.core.StateManager;
import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.model.flow.StepType;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultFlowStepFactoryTest {

    private final DefaultFlowStepFactory factory = new DefaultFlowStepFactory(
            mock(ComponentCatalog.class), mock(DynamicWorkflowService.class),
            mock(EventStreamRegistry.class), mock(StateManager.class));

    @Test
    void buildsOrchestrateStepForOrchestrateType() {
        FlowStep step = factory.create(Map.of("id", "o1", "type", "ORCHESTRATE", "config", Map.of("goal", "g")));

        assertThat(step).isInstanceOf(OrchestrateStep.class);
        assertThat(step.getType()).isEqualTo(StepType.ORCHESTRATE);
    }

    @Test
    void buildsComponentStepForRegularNodes() {
        FlowStep step = factory.create(Map.of("id", "s1", "componentId", "llm-chat"));

        assertThat(step).isInstanceOf(ComponentStep.class);
    }

    @Test
    void readsEditorPersistedOperationConfigurationAndConnectionsForComponentNodes() throws Exception {
        ComponentCatalog catalog = mock(ComponentCatalog.class);
        AIComponent component = mock(AIComponent.class);
        ExecutionContext ctx = mock(ExecutionContext.class);
        when(ctx.get("input")).thenReturn(Optional.of("in"));
        when(catalog.getComponent("llm-chat")).thenReturn(Optional.of(component));
        when(component.execute(any(), any(), any())).thenReturn("out");

        FlowStep step = new DefaultFlowStepFactory(
                catalog, mock(DynamicWorkflowService.class),
                mock(EventStreamRegistry.class), mock(StateManager.class))
                .create(Map.of(
                        "id", "s1",
                        "type", "TOOL",
                        "componentId", "llm-chat",
                        "operation", "summarize",
                        "configuration", Map.of("temperature", 0.1),
                        "connections", List.of(Map.of(
                                "sourceId", "s1",
                                "targetId", "s2",
                                "condition", "ok",
                                "isErrorPath", false))));

        assertThat(step.getConnections()).hasSize(1);
        StepConnection connection = step.getConnections().get(0);
        assertThat(connection.getSourceId()).isEqualTo("s1");
        assertThat(connection.getTargetId()).isEqualTo("s2");
        assertThat(connection.getCondition()).contains("ok");
        assertThat(connection.isErrorPath()).isFalse();

        step.execute(ctx).join();

        verify(component).execute(eq("summarize"), eq("in"), eq(ctx));
    }

    @Test
    void readsEditorPersistedConfigurationForOrchestrateNodes() {
        DynamicWorkflowService service = mock(DynamicWorkflowService.class);
        ExecutionContext ctx = mock(ExecutionContext.class);
        when(ctx.get("input")).thenReturn(Optional.empty());
        when(service.runOn(any(), eq(ctx), any())).thenReturn(new SupervisorResult(List.of("done"), 1));

        FlowStep step = new DefaultFlowStepFactory(
                mock(ComponentCatalog.class), service,
                mock(EventStreamRegistry.class), mock(StateManager.class))
                .create(Map.of(
                        "id", "o1",
                        "type", "ORCHESTRATE",
                        "configuration", Map.of("goal", "audit"),
                        "connections", List.of(Map.of(
                                "sourceId", "o1",
                                "targetId", "recover",
                                "isErrorPath", true))));

        assertThat(step.getConnections()).hasSize(1);
        assertThat(step.getConnections().get(0).isErrorPath()).isTrue();

        step.execute(ctx).join();

        ArgumentCaptor<br.com.archflow.api.orchestration.DynamicWorkflowRequest> req =
                ArgumentCaptor.forClass(br.com.archflow.api.orchestration.DynamicWorkflowRequest.class);
        verify(service).runOn(req.capture(), eq(ctx), any());
        assertThat(req.getValue().goal()).isEqualTo("audit");
    }
}
