package br.com.archflow.langchain4j.core.exception;

/**
 * Exceção base para erros dos adapters
 */
public class LangChainAdapterException extends RuntimeException {
    public LangChainAdapterException(String message) {
        super(message);
    }

    public LangChainAdapterException(String message, Throwable cause) {
        super(message, cause);
    }
}
