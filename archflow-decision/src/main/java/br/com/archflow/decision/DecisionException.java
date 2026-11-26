package br.com.archflow.decision;

/**
 * Uma decisão que não saiu. {@link #retryable()} separa o que melhora tentando de novo (limite de
 * taxa, sobrecarga, rede) do que não melhora (chave inválida, pedido recusado, resposta ilegível).
 */
public class DecisionException extends Exception {

    private final boolean retryable;
    /** O que a chamada consumiu antes de falhar — ver {@link #withUsage}. */
    private DecisionResult.Usage usage;

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

    /**
     * A chamada chegou a ser feita, e cobrada, antes de a decisão falhar — o modelo respondeu sem
     * JSON, ou sem uma das perguntas. Sem isto a falha some do consumo: o nó relatava uso só quando
     * a decisão saía, e a chamada perdida não chegava ao teto de custo (RCHFLW-170).
     *
     * @return esta mesma exceção, para lançar na mesma linha
     */
    public DecisionException withUsage(DecisionResult.Usage usage) {
        this.usage = usage;
        return this;
    }

    /** O consumo da chamada que falhou; nulo quando ela não chegou a consumir, ou não se sabe. */
    public DecisionResult.Usage usage() {
        return usage;
    }
}
