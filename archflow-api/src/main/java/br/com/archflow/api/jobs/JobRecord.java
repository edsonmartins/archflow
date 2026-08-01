package br.com.archflow.api.jobs;

import java.time.Instant;
import java.util.Map;

/** Durable snapshot of one asynchronous job. */
public record JobRecord(
        String id,
        String tenantId,
        String workspaceId,
        String type,
        JobStatus status,
        Map<String, Object> payload,
        int progress,
        String message,
        int attempt,
        int maxAttempts,
        String idempotencyKey,
        boolean cancelRequested,
        String workerId,
        String error,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Instant updatedAt) {
}
