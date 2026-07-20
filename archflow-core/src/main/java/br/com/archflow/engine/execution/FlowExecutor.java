package br.com.archflow.engine.execution;

import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.model.flow.StepResult;

public interface FlowExecutor {
    /**
     * Executa um fluxo específico
     */
    FlowResult execute(Flow flow, ExecutionContext context);

    /**
     * Executa um fluxo com um sinal cooperativo de pause/cancel.
     * Implementações que suportam interrupção devem sobrescrever este método;
     * o default ignora o sinal.
     */
    default FlowResult execute(Flow flow, ExecutionContext context, FlowControl control) {
        return execute(flow, context);
    }

    /**
     * Processa o resultado de um passo
     */
    void handleResult(StepResult result);
}