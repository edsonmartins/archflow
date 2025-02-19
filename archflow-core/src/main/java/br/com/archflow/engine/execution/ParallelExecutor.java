package br.com.archflow.engine.execution;

import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepResult;

import java.util.List;

public interface ParallelExecutor {
    /**
     * Executa passos em paralelo
     */
    List<StepResult> executeParallel(List<FlowStep> steps);
    
    /**
     * Aguarda a conclusão da execução paralela
     */
    void awaitCompletion();
}