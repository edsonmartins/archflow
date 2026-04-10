package br.com.archflow.engine.scheduler;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Representação de um job agendado por tenant.
 *
 * <p>O ArchFlow não conhece a semântica do job — produtos como VendaX
 * definem o {@code agentId} e o {@code payload} conforme suas necessidades.
 *
 * @param tenantId       Identificador do tenant (obrigatório)
 * @param jobId          Identificador único do job dentro do tenant
 * @param cronExpression Expressão cron (ex: "0 0 8 ? * MON")
 * @param agentId        ID do agente a ser acionado quando o job disparar
 * @param payload        Dados arbitrários passados ao agente via ExecutionContext.variables
 * @param enabled        Se o job está ativo
 * @param createdAt      Timestamp de criação
 */
public record ScheduledJob(
        String tenantId,
        String jobId,
        String cronExpression,
        String agentId,
        Map<String, Object> payload,
        boolean enabled,
        Instant createdAt
) {
    public ScheduledJob {
        Objects.requireNonNull(tenantId, "tenantId is required");
        Objects.requireNonNull(jobId, "jobId is required");
        Objects.requireNonNull(cronExpression, "cronExpression is required");
        Objects.requireNonNull(agentId, "agentId is required");
        if (payload == null) payload = Map.of();
        if (createdAt == null) createdAt = Instant.now();
    }

    /**
     * Cria um ScheduledJob com valores default (enabled=true, createdAt=now).
     */
    public static ScheduledJob of(String tenantId, String jobId, String cronExpression,
                                   String agentId, Map<String, Object> payload) {
        return new ScheduledJob(tenantId, jobId, cronExpression, agentId, payload, true, null);
    }

    /**
     * Retorna uma cópia com nova expressão cron.
     */
    public ScheduledJob withCron(String newCron) {
        return new ScheduledJob(tenantId, jobId, newCron, agentId, payload, enabled, createdAt);
    }

    /**
     * Retorna uma cópia com status enabled alterado.
     */
    public ScheduledJob withEnabled(boolean newEnabled) {
        return new ScheduledJob(tenantId, jobId, cronExpression, agentId, payload, newEnabled, createdAt);
    }
}
