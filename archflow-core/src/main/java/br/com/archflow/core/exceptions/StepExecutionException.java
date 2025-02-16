package br.com.archflow.core.exceptions;

import br.com.archflow.model.flow.StepError;

/**
 * Exceção lançada durante execução de um passo.
 */
public class StepExecutionException extends FlowException {
    private final String stepId;
    private final StepError error;

    public StepExecutionException(String stepId, StepError error) {
        super("Step execution failed: " + stepId);
        this.stepId = stepId;
        this.error = error;
    }

    public String getStepId() {
        return stepId;
    }

    public StepError getError() {
        return error;
    }
}