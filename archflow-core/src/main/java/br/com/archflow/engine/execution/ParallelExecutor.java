package br.com.archflow.engine.execution;

import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepResult;

import java.util.List;

public interface ParallelExecutor {
    /**
     * Executa passos em paralelo
     *
     * @param steps Lista de passos a executar
     * @param context Contexto de execução
     * @return Lista de resultados
     */
    List<StepResult> executeParallel(List<FlowStep> steps, ExecutionContext context);

    /**
     * Aguarda a conclusão da execução paralela
     */
    void awaitCompletion();
}