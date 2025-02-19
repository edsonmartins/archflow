package br.com.archflow.model.ai;


import br.com.archflow.model.ai.domain.*;
import br.com.archflow.model.engine.ExecutionContext;

import java.util.List;

/**
 * Interface base para Agentes de IA.
 */
public interface AIAgent extends AIComponent {
    /**
     * Executa uma tarefa específica.
     */
    Result executeTask(Task task, ExecutionContext context);

    /**
     * Toma uma decisão baseada no contexto atual.
     */
    Decision makeDecision(ExecutionContext context);

    /**
     * Planeja ações para atingir um objetivo.
     */
    List<Action> planActions(Goal goal, ExecutionContext context);
}