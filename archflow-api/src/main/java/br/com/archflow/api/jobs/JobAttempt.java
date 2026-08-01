package br.com.archflow.api.jobs;

import java.time.Instant;

/** Audit record for one worker claim of a job. */
public record JobAttempt(
        String jobId,
        int attempt,
        String workerId,
        Instant startedAt,
        Instant completedAt,
        JobStatus outcome,
        String error) {
}
