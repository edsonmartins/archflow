package br.com.archflow.api.flow;

import br.com.archflow.agent.orchestration.SupervisorResult;
import br.com.archflow.api.orchestration.DynamicWorkflowRequest;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.StepStatus;
import br.com.archflow.model.flow.StepResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrchestrateStepTest {

    private DynamicWorkflowService service;
    private ExecutionContext ctx;

    @BeforeEach
    void setup() {
        service = mock(DynamicWorkflowService.class);
        ctx = mock(ExecutionContext.class);
        when(ctx.get(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void runsDynamicWorkflowFromConfigAndOutputsConfirmedFindings() {
        when(service.runOn(any(), eq(ctx), any())).thenReturn(new SupervisorResult(List.<Object>of("a", "b"), 2));

        var step = new OrchestrateStep("o1", List.of(), Map.of("goal", "audit", "voters", 3), service, null);
        StepResult result = step.execute(ctx).join();

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).contains(List.of("a", "b"));
        verify(ctx).set("o1", List.of("a", "b"));
        verify(ctx).set("input", List.of("a", "b"));

        ArgumentCaptor<DynamicWorkflowRequest> req = ArgumentCaptor.forClass(DynamicWorkflowRequest.class);
        verify(service).runOn(req.capture(), eq(ctx), any());
        assertThat(req.getValue().goal()).isEqualTo("audit");
        assertThat(req.getValue().voters()).isEqualTo(3);
    }

    @Test
    void fallsBackToInputAsGoal() {
        when(ctx.get("input")).thenReturn(Optional.of("audit from input"));
        when(service.runOn(any(), any(), any())).thenReturn(new SupervisorResult(List.of(), 1));

        var step = new OrchestrateStep("o1", List.of(), Map.of(), service, null);
        step.execute(ctx).join();

        ArgumentCaptor<DynamicWorkflowRequest> req = ArgumentCaptor.forClass(DynamicWorkflowRequest.class);
        verify(service).runOn(req.capture(), any(), any());
        assertThat(req.getValue().goal()).isEqualTo("audit from input");
    }

    @Test
    void failsWhenNoGoalOrInput() {
        var step = new OrchestrateStep("o1", List.of(), Map.of(), service, null);
        StepResult result = step.execute(ctx).join();

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        verifyNoInteractions(service);
    }
}
