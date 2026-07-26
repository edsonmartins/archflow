package br.com.archflow.engine.persistence;

import br.com.archflow.engine.core.StateManager;
import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.flow.FlowStatus;
import br.com.archflow.model.flow.StateUpdate;

import java.util.List;
import java.util.Objects;

/**
 * Adapta um {@link StateRepository} (persistência durável, ex.:
 * {@link br.com.archflow.engine.persistence.jdbc.JdbcStateRepository}) ao
 * contrato {@link StateManager} consumido pelo engine.
 *
 * <p>O engine fala {@code StateManager} (saveState/loadState/updateState),
 * enquanto a implementação JDBC fala {@code StateRepository}
 * (saveState/getState). Sem esta ponte não havia como tornar o estado de
 * execução durável no caminho do engine.
 */
public class RepositoryStateManager implements StateManager {

    private final StateRepository repository;

    public RepositoryStateManager(StateRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public void saveState(String flowId, FlowState state) {
        repository.saveState(flowId, state);
    }

    @Override
    public FlowState loadState(String flowId) {
        return repository.getState(flowId);
    }

    @Override
    public List<FlowState> findByStatus(FlowStatus status) {
        if (status == null) {
            return List.of();
        }
        return repository.getStatesByStatus(status.name());
    }

    @Override
    public void updateState(String flowId, StateUpdate update) {
        if (update == null) {
            return;
        }
        FlowState state = repository.getState(flowId);
        if (state == null) {
            return;
        }
        update.apply(state);
        repository.saveState(flowId, state);
    }
}
