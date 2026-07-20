package br.com.archflow.engine.execution;

/**
 * Sinal cooperativo de controle de uma execução de fluxo.
 *
 * <p>O executor consulta este sinal entre steps: {@code isStopRequested}
 * encerra a travessia com status CANCELLED e {@code isPauseRequested}
 * a suspende com status PAUSED (o estado corrente fica no
 * {@code ExecutionContext} para posterior resume).
 */
public interface FlowControl {

    /** @return {@code true} se uma pausa foi solicitada para o fluxo */
    boolean isPauseRequested();

    /** @return {@code true} se o cancelamento do fluxo foi solicitado */
    boolean isStopRequested();

    /** Sinal inerte — nunca pausa nem cancela. */
    FlowControl NONE = new FlowControl() {
        @Override public boolean isPauseRequested() { return false; }
        @Override public boolean isStopRequested() { return false; }
    };
}
