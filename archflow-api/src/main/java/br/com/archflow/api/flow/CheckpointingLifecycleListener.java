package br.com.archflow.api.flow;

import br.com.archflow.engine.core.StateManager;
import br.com.archflow.engine.lifecycle.FlowLifecycleListener;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.flow.FlowStep;

import java.util.HashMap;
import java.util.logging.Logger;

/**
 * Checkpoint durável por step (fecha o item 1.7 do plano de homologação):
 * após cada step concluído/falho, sincroniza as variáveis do contexto
 * (outputs, {@code __archflow.completedSteps}) para o {@link FlowState} e o
 * persiste. Com isso um crash no meio do fluxo deixa no banco o último
 * checkpoint — o resume incremental (1.9) retoma dos steps concluídos em vez
 * de reexecutar tudo.
 *
 * <p>Falha de persistência não derruba o fluxo: o executor engole exceções de
 * listener (safeLifecycle) e o estado terminal ainda é salvo pelo engine.
 */
public final class CheckpointingLifecycleListener implements FlowLifecycleListener {

    private static final Logger logger = Logger.getLogger(CheckpointingLifecycleListener.class.getName());

    private final StateManager stateManager;

    public CheckpointingLifecycleListener(StateManager stateManager) {
        this.stateManager = stateManager;
    }

    @Override
    public void onStepCompleted(Flow flow, FlowStep step, ExecutionContext context, long durationMs) {
        checkpoint(flow, context);
    }

    @Override
    public void onStepFailed(Flow flow, FlowStep step, ExecutionContext context,
                             Throwable error, long durationMs) {
        checkpoint(flow, context);
    }

    @Override
    public void onStepSkipped(Flow flow, FlowStep step, ExecutionContext context) {
        checkpoint(flow, context);
    }

    private void checkpoint(Flow flow, ExecutionContext context) {
        try {
            FlowState state = context.getState();
            if (state == null) {
                return;
            }
            if (context.getVariables() != null) {
                state.setVariables(new HashMap<>(context.getVariables()));
            }
            stateManager.saveState(flow.getId(), state);
        } catch (Exception e) {
            logger.warning("Falha ao persistir checkpoint do fluxo " + flow.getId()
                    + ": " + e.getMessage());
        }
    }
}
