package br.com.archflow.engine.execution;

/**
 * Sinal cooperativo de controle de uma execução de fluxo.
 *
 * <p>O executor consulta este sinal entre steps e ao fim da travessia:
 * {@code isStopRequested} encerra a travessia com status CANCELLED e
 * {@code isPauseRequested} a suspende com status PAUSED (o estado corrente fica
 * no {@code ExecutionContext} para posterior resume). A consulta ao fim existe
 * porque o sinal pode ser levantado durante o último step — um nó de aprovação
 * como último passo, ou um cancelamento enquanto ele roda — e não pode se perder
 * só porque não havia próximo step.
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
