package br.com.archflow.decision;

/**
 * Uma decisão que não saiu. {@link #retryable()} separa o que melhora tentando de novo (limite de
 * taxa, sobrecarga, rede) do que não melhora (chave inválida, pedido recusado, resposta ilegível).
 */
public class DecisionException extends Exception {

    private final boolean retryable;

    public DecisionException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public DecisionException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
