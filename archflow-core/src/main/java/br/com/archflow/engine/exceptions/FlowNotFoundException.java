package br.com.archflow.engine.exceptions;

/**
 * Exceção lançada quando um fluxo não é encontrado.
 */
public class FlowNotFoundException extends FlowException {
    private final String flowId;

    public FlowNotFoundException(String flowId) {
        super("Flow not found: " + flowId);
        this.flowId = flowId;
    }

    public String getFlowId() {
        return flowId;
    }
}