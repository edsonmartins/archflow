package br.com.archflow.engine.scheduler.dlq;

import br.com.archflow.engine.scheduler.ScheduledJob;

import java.util.List;

/**
 * Fila de jobs falhados (Dead Letter Queue) com isolamento por tenant.
 *
 * <p>Quando um job agendado falha durante execução, ele é enviado para a DLQ.
 * Produtos podem consultar e reprocessar jobs falhados conforme necessário.
 */
public interface DeadLetterQueue {

    /**
     * Enfileira um job falhado.
     *
     * @param job       O job que falhou
     * @param exception A exceção que causou a falha
     */
    void enqueue(ScheduledJob job, Exception exception);

    /**
     * Lista jobs falhados de um tenant.
     *
     * @param tenantId ID do tenant
     * @return Lista de entradas na DLQ
     */
    List<FailedJobEntry> listByTenant(String tenantId);

    /**
     * Remove uma entrada da DLQ (após reprocessamento ou descarte).
     *
     * @param tenantId ID do tenant
     * @param jobId    ID do job
     * @return true se removido
     */
    boolean remove(String tenantId, String jobId);

    /**
     * Limpa todas as entradas de um tenant.
     *
     * @param tenantId ID do tenant
     * @return Número de entradas removidas
     */
    int clearByTenant(String tenantId);

    /**
     * Retorna o total de entradas na DLQ.
     */
    int size();
}
