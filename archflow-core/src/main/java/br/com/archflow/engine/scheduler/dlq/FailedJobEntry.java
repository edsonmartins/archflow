package br.com.archflow.engine.scheduler.dlq;

import br.com.archflow.engine.scheduler.ScheduledJob;

import java.time.Instant;

/**
 * Entrada na Dead Letter Queue representando um job falhado.
 *
 * @param job         O job que falhou
 * @param errorMessage Mensagem de erro
 * @param retryCount  Número de tentativas realizadas
 * @param failedAt    Timestamp da última falha
 */
public record FailedJobEntry(
        ScheduledJob job,
        String errorMessage,
        int retryCount,
        Instant failedAt
) {
    public FailedJobEntry {
        if (failedAt == null) failedAt = Instant.now();
    }

    public static FailedJobEntry of(ScheduledJob job, Exception exception) {
        return new FailedJobEntry(
                job,
                exception != null ? exception.getMessage() : "Unknown error",
                1,
                Instant.now()
        );
    }

    public FailedJobEntry incrementRetry() {
        return new FailedJobEntry(job, errorMessage, retryCount + 1, Instant.now());
    }
}
