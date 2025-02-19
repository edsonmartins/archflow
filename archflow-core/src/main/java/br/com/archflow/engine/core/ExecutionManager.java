package br.com.archflow.engine.core;

import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.FlowResult;

public interface ExecutionManager {
    /**
     * Gerencia a execução de um fluxo
     */
    FlowResult executeFlow(Flow flow, ExecutionContext context);
    
    /**
     * Pausa a execução de um fluxo
     */
    void pauseFlow(String flowId);
    
    /**
     * Para a execução de um fluxo
     */
    void stopFlow(String flowId);
}