package br.com.archflow.api.triggers.dto;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Cron-scheduled trigger that fires agent invocations on a recurring
 * schedule.
 *
 * <p>Stored server-side (in-memory by default) and materialised as a
 * Quartz job + cron trigger at create / update time. When the trigger
 * fires, the job submits an {@code InvocationRequest} to the
 * configured {@code AgentInvocationQueue}.</p>
 *
 * @param id             stable identifier; generated if absent
 * @param name           human-readable label shown in the UI
 * @param cronExpression Quartz cron expression (6 or 7 fields; seconds
 *                       minutes hours dom month dow [year])
 * @param tenantId       tenant that will own each invocation
 * @param agentId        target agent id (must exist in the catalog)
 * @param payload        fixed payload forwarded as the invocation body
 * @param enabled        when {@code false} the trigger stays configured
 *                       but paused — Quartz won't schedule it
 * @param createdAt      server clock at creation
 * @param lastFiredAt    last successful fire time, or {@code null}
 * @param lastError      last error message, or {@code null}
 */
public record ScheduledTriggerDto(
        String id,
        String name,
        String cronExpression,
        String tenantId,
        String agentId,
        Map<String, Object> payload,
        boolean enabled,
        Instant createdAt,
        Instant lastFiredAt,
        String lastError) {

    public ScheduledTriggerDto {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(cronExpression, "cronExpression");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(agentId, "agentId");
        if (payload == null) payload = Map.of();
        if (createdAt == null) createdAt = Instant.now();
    }
}
