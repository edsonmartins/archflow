package br.com.archflow.langchain4j.realtime.spi;

/**
 * Checked exception thrown by realtime adapters when session
 * provisioning or communication fails.
 */
public class RealtimeException extends Exception {

    public RealtimeException(String message) {
        super(message);
    }

    public RealtimeException(String message, Throwable cause) {
        super(message, cause);
    }
}
