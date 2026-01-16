package br.com.archflow.agent.tool;

/**
 * Exceção lançada por interceptores de tool para sinalizar problemas
 * ou interromper a execução da cadeia.
 */
public class ToolInterceptorException extends RuntimeException {

    private final String interceptorName;
    private final String executionId;

    public ToolInterceptorException(String message) {
        super(message);
        this.interceptorName = null;
        this.executionId = null;
    }

    public ToolInterceptorException(String message, Throwable cause) {
        super(message, cause);
        this.interceptorName = null;
        this.executionId = null;
    }

    public ToolInterceptorException(String interceptorName, String executionId, String message) {
        super(message);
        this.interceptorName = interceptorName;
        this.executionId = executionId;
    }

    public ToolInterceptorException(String interceptorName, String executionId, String message, Throwable cause) {
        super(message, cause);
        this.interceptorName = interceptorName;
        this.executionId = executionId;
    }

    public String getInterceptorName() {
        return interceptorName;
    }

    public String getExecutionId() {
        return executionId;
    }

    public boolean shouldAbort() {
        // Por padrão, exceções de interceptor abortam a execução
        return true;
    }
}
