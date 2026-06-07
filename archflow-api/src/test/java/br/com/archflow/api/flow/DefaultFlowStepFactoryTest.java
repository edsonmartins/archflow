package br.com.archflow.api.flow;

import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.engine.core.StateManager;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepType;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
}
