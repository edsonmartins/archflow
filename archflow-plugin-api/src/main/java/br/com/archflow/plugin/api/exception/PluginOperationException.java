package br.com.archflow.plugin.api.exception;

/**
 * Exceção lançada quando há erro na execução de uma operação do plugin.
 */
public class PluginOperationException extends PluginException {
    private final String operationId;

    public PluginOperationException(String operationId, String message) {
        super(message);
        this.operationId = operationId;
    }

    public PluginOperationException(String operationId, String message, Throwable cause) {
        super(message, cause);
        this.operationId = operationId;
    }

    public String getOperationId() {
        return operationId;
    }
}