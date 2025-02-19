package br.com.archflow.engine.core;

import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.flow.StateUpdate;


public interface StateManager {
    /**
     * Salva o estado do fluxo
     */
    void saveState(String flowId, FlowState state);
    
    /**
     * Carrega o estado do fluxo
     */
    FlowState loadState(String flowId);
    
    /**
     * Atualiza o estado do fluxo
     */
    void updateState(String flowId, StateUpdate update);
}