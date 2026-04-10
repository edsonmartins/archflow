package br.com.archflow.engine.scheduler;

import java.util.List;
import java.util.Optional;

/**
 * Scheduler genérico com isolamento por tenant.
 *
 * <p>Permite que produtos construídos sobre o ArchFlow registrem jobs cron
 * por tenant sem que o motor conheça a semântica de negócio dos jobs.
 *
 * <p>Cada job é identificado pela chave composta {@code (tenantId, jobId)}.
 * Falha no job de um tenant não afeta outros tenants.
 *
 * <p>Exemplo de uso:
 * <pre>{@code
 * scheduler.schedule(ScheduledJob.of(
 *     "tenant-1", "weekly-briefing",
 *     "0 0 8 ? * MON", "briefing-agent",
 *     Map.of("type", "weekly")
 * ));
 * }</pre>
 */
public interface TenantScheduler {

    /**
     * Registra um novo job agendado.
     *
     * @param job O job a ser agendado
     * @throws IllegalArgumentException se já existir um job com o mesmo (tenantId, jobId)
     * @throws IllegalArgumentException se a expressão cron for inválida
     */
    void schedule(ScheduledJob job);

    /**
     * Reagenda um job existente com nova expressão cron, sem restart.
     *
     * @param tenantId ID do tenant
     * @param jobId    ID do job
     * @param newCron  Nova expressão cron
     * @throws IllegalArgumentException se o job não existir
     */
    void reschedule(String tenantId, String jobId, String newCron);

    /**
     * Cancela um job agendado.
     *
     * @param tenantId ID do tenant
     * @param jobId    ID do job
     * @return true se o job foi cancelado, false se não existia
     */
    boolean cancel(String tenantId, String jobId);

    /**
     * Pausa um job sem removê-lo.
     *
     * @param tenantId ID do tenant
     * @param jobId    ID do job
     */
    void pause(String tenantId, String jobId);

    /**
     * Retoma um job pausado.
     *
     * @param tenantId ID do tenant
     * @param jobId    ID do job
     */
    void resume(String tenantId, String jobId);

    /**
     * Lista todos os jobs de um tenant.
     *
     * @param tenantId ID do tenant
     * @return Lista de jobs agendados
     */
    List<ScheduledJob> listByTenant(String tenantId);

    /**
     * Obtém um job específico.
     *
     * @param tenantId ID do tenant
     * @param jobId    ID do job
     * @return O job ou empty se não existir
     */
    Optional<ScheduledJob> getJob(String tenantId, String jobId);

    /**
     * Cancela todos os jobs de um tenant.
     *
     * @param tenantId ID do tenant
     * @return Número de jobs cancelados
     */
    int cancelAllByTenant(String tenantId);
}
