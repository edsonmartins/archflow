package br.com.archflow.api.flow;

import br.com.archflow.agent.orchestration.OrchestrationListener;
import br.com.archflow.model.flow.ExecutionPath;
import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.flow.PathStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MaterializingOrchestrationListenerTest {

    @Test
    void materializesRoundsAndSubtasksAsExecutionPaths() {
        FlowState state = FlowState.builder().flowId("exec-1").build();
        var listener = new MaterializingOrchestrationListener(state, OrchestrationListener.NOOP);

        listener.onPlanned(1, List.of("subtask-a", "subtask-b"));
        listener.onVerified(1, "subtask-a", true);
        listener.onRoundCompleted(1, true, 1);
        listener.onConverged(1, 1);

        List<ExecutionPath> paths = state.getExecutionPaths();
        assertThat(paths).hasSize(1);
        ExecutionPath round = paths.get(0);
        assertThat(round.getPathId()).isEqualTo("round-1");
        assertThat(round.getStatus()).isEqualTo(PathStatus.COMPLETED);
        assertThat(round.getParallelBranches()).extracting(ExecutionPath::getPathId)
                .containsExactly("subtask-a", "subtask-b");
        assertThat(round.getParallelBranches()).allMatch(b -> b.getStatus() == PathStatus.COMPLETED);
    }
}
