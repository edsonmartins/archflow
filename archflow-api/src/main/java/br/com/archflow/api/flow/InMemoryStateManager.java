package br.com.archflow.api.flow;

import br.com.archflow.engine.core.StateManager;
import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.flow.StateUpdate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link StateManager} for dev/demo (design-0005 step 1). Keeps flow
 * state in a map so the engine can run and resume without a database; the JDBC/
 * Redis impl is the production swap.
 */
public final class InMemoryStateManager implements StateManager {

    private final Map<String, FlowState> states = new ConcurrentHashMap<>();

    @Override
    public void saveState(String flowId, FlowState state) {
        if (flowId != null && state != null) {
            states.put(flowId, state);
        }
    }

    @Override
    public FlowState loadState(String flowId) {
        return states.get(flowId);
    }

    @Override
    public void updateState(String flowId, StateUpdate update) {
        FlowState state = states.get(flowId);
        if (state != null && update != null) {
            update.apply(state);
        }
    }
}
