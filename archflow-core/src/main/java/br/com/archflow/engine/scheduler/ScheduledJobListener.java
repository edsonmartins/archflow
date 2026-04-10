package br.com.archflow.engine.scheduler;

/**
 * Listener notificado quando um job agendado é disparado.
 *
 * <p>O produto que utiliza o ArchFlow registra um listener para reagir
 * ao disparo de jobs — tipicamente montando um {@code ExecutionContext}
 * e acionando o agente configurado no job.
 */
@FunctionalInterface
public interface ScheduledJobListener {

    /**
     * Chamado quando um job agendado é disparado pelo scheduler.
     *
     * @param job O job que foi disparado
     */
    void onJobTriggered(ScheduledJob job);
}
