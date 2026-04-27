package br.com.archflow.model.escalation;

/** Signals that handing a conversation off to a human operator failed. */
public class EscalationException extends RuntimeException {
    public EscalationException(String message) { super(message); }
    public EscalationException(String message, Throwable cause) { super(message, cause); }
}
