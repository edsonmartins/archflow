package br.com.archflow.api.flow;

import br.com.archflow.agent.orchestration.OrchestrationListener;
import br.com.archflow.model.flow.ExecutionPath;
import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.flow.PathStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Materializes a dynamic workflow's rounds/subtasks as {@link ExecutionPath}s on
 * the flow's {@link FlowState} (design-0005 step 4), so the dynamic tree is
 * persisted and inspectable (e.g. via the execution status endpoint / canvas).
 * Wraps a {@code delegate} listener (typically the streaming one) so a single
 * listener both streams and materializes.
 *
 * <p>The supervisor drives rounds sequentially, so path mutation here is not
 * concurrent; collections are still thread-safe to be defensive.
 */
public final class MaterializingOrchestrationListener implements OrchestrationListener {

    private final FlowState state;
    private final OrchestrationListener delegate;
    private final Map<Integer, ExecutionPath> roundPaths = new ConcurrentHashMap<>();

    public MaterializingOrchestrationListener(FlowState state, OrchestrationListener delegate) {
        this.state = state;
        this.delegate = delegate == null ? OrchestrationListener.NOOP : delegate;
    }

    private List<ExecutionPath> paths() {
        if (state.getExecutionPaths() == null) {
            state.setExecutionPaths(new CopyOnWriteArrayList<>());
        }
        return state.getExecutionPaths();
    }

    @Override
    public void onPlanned(int round, List<?> items) {
        delegate.onPlanned(round, items);
        List<ExecutionPath> branches = new ArrayList<>();
        for (Object item : items) {
            branches.add(ExecutionPath.builder()
                    .pathId(String.valueOf(item))
                    .status(PathStatus.RUNNING)
                    .build());
        }
        ExecutionPath roundPath = ExecutionPath.builder()
                .pathId("round-" + round)
                .status(PathStatus.RUNNING)
                .parallelBranches(branches)
                .build();
        roundPaths.put(round, roundPath);
        paths().add(roundPath);
    }

    @Override
    public void onVerified(int round, Object finding, boolean confirmed) {
        delegate.onVerified(round, finding, confirmed);
    }

    @Override
    public void onRoundCompleted(int round, boolean producedNew, int confirmedSoFar) {
        delegate.onRoundCompleted(round, producedNew, confirmedSoFar);
        ExecutionPath roundPath = roundPaths.get(round);
        if (roundPath != null) {
            roundPath.setStatus(PathStatus.COMPLETED);
            if (roundPath.getParallelBranches() != null) {
                roundPath.getParallelBranches().forEach(b -> b.setStatus(PathStatus.COMPLETED));
            }
        }
    }

    @Override
    public void onConverged(int rounds, int confirmedTotal) {
        delegate.onConverged(rounds, confirmedTotal);
    }
}
