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

    /**
     * Os tipos de {@link Task} que {@link #executeTask} aceita, o primeiro sendo o tipo padrão do agente.
     *
     * <p>Existe porque quem despacha trabalho sem conhecer o agente (o worker da orquestração dinâmica)
     * precisa montar uma {@code Task} que ele aceite. Sem a declaração, o worker inventava um tipo
     * ({@code orchestration.subtask}) que nenhum agente reconhecia, e toda subtarefa voltava "Unsupported
     * task type" (RCHFLW-61). Lista vazia = o agente não declara, e quem despacha deve recusar com esse
     * motivo em vez de adivinhar um tipo.</p>
     */
    default List<String> supportedTaskTypes() {
        return List.of();
    }
}