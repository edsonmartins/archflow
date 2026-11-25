package br.com.archflow.api.jobs;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JobServiceTest {

    private final JobService service = new JobService(new InMemoryJobRepository());

    @Test
    void idempotencyReturnsTheOriginalJob() {
        JobRecord first = service.submit(
                "tenant", null, "INGEST", Map.of("file", "a"), "same", 3);
        JobRecord second = service.submit(
                "tenant", null, "INGEST", Map.of("file", "b"), "same", 3);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.payload()).containsEntry("file", "a");
    }

    @Test
    void progressesAndCompletesClaimedJob() {
        JobRecord submitted = service.submit(
                "tenant", "workspace", "INGEST", Map.of(), null, 3);

        JobRecord claimed = service.claim("worker-1");
        JobRecord progress = service.progress(claimed.id(), 45, "Parsing");
        JobRecord completed = service.complete(claimed.id());

        assertThat(claimed.id()).isEqualTo(submitted.id());
        assertThat(claimed.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(claimed.attempt()).isEqualTo(1);
        assertThat(progress.progress()).isEqualTo(45);
        assertThat(completed.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(completed.progress()).isEqualTo(100);
    }

    @Test
    void retriesUntilMaxAttemptsThenFails() {
        JobRecord submitted = service.submit(
                "tenant", null, "EVALUATE", Map.of(), null, 2);

        service.claim("worker-1");
        JobRecord retry = service.fail(submitted.id(), "temporary");
        JobRecord secondClaim = service.claim("worker-2");
        JobRecord failed = service.fail(submitted.id(), "permanent");

        assertThat(retry.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(secondClaim.attempt()).isEqualTo(2);
        assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.error()).isEqualTo("permanent");
    }

    @Test
    void cancellationIsTenantScopedAndCooperative() {
        JobRecord submitted = service.submit(
                "tenant-a", null, "INGEST", Map.of(), null, 3);

        assertThat(service.cancel("tenant-b", submitted.id())).isNull();
        JobRecord running = service.claim("worker");
        JobRecord requested = service.cancel("tenant-a", submitted.id());
        JobRecord terminal = service.complete(running.id());

        assertThat(requested.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(requested.cancelRequested()).isTrue();
        assertThat(terminal.status()).isEqualTo(JobStatus.CANCELLED);
    }

    @Test
    void queuedCancellationNeverReachesAWorker() {
        JobRecord first = service.submit(
                "tenant", null, "INGEST", Map.of(), null, 3);
        JobRecord second = service.submit(
                "tenant", null, "INGEST", Map.of(), null, 3);

        JobRecord cancelled = service.cancel("tenant", first.id());
        JobRecord claimed = service.claim("worker");

        assertThat(cancelled.status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(claimed.id()).isEqualTo(second.id());
    }

    @Test
    void reopenGivesAFailedJobANewAttemptBudget() {
        JobRecord submitted = service.submit(
                "tenant", null, "INGEST", Map.of("fileId", "file-1"), "same", 1);
        service.claim("worker-1");
        service.fail(submitted.id(), "permanent");

        assertThat(service.reopen("tenant-b", submitted.id(), 2, Map.of())).isNull();
        JobRecord reopened = service.reopen("tenant", submitted.id(), 2, Map.of("fileId", "file-2"));
        JobRecord again = service.reopen("tenant", submitted.id(), 2, Map.of("fileId", "file-3"));
        JobRecord claimed = service.claim("worker-2");
        JobRecord retry = service.fail(submitted.id(), "again");

        assertThat(reopened.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(reopened.error()).isNull();
        assertThat(reopened.completedAt()).isNull();
        assertThat(reopened.maxAttempts()).isEqualTo(3);
        assertThat(reopened.payload()).as("a nova tentativa leva a entrada nova")
                .containsEntry("fileId", "file-2");
        assertThat(again).as("o segundo chamador sabe que não foi ele quem reabriu").isNull();
        assertThat(claimed.payload()).containsEntry("fileId", "file-2");
        assertThat(claimed.id()).isEqualTo(submitted.id());
        assertThat(claimed.attempt()).as("a numeração continua").isEqualTo(2);
        assertThat(retry.status()).as("ainda resta uma tentativa").isEqualTo(JobStatus.QUEUED);
        assertThat(service.findByIdempotencyKey("tenant", "INGEST", "same").id())
                .isEqualTo(submitted.id());
    }

    @Test
    void reopenClearsTheCancellationAndLeavesOtherStatesAlone() {
        JobRecord cancelled = service.submit("tenant", null, "INGEST", Map.of(), null, 3);
        service.cancel("tenant", cancelled.id());
        JobRecord done = service.submit("tenant", null, "INGEST", Map.of(), null, 3);

        JobRecord reopened = service.reopen("tenant", cancelled.id(), 3, Map.of());
        JobRecord stillQueued = service.reopen("tenant", done.id(), 3, Map.of("x", "y"));

        assertThat(reopened.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(reopened.cancelRequested()).isFalse();
        assertThat(stillQueued).isNull();
        assertThat(service.get("tenant", done.id())).isEqualTo(done);
        assertThat(java.util.List.of(service.claim("w1").id(), service.claim("w2").id()))
                .as("o claim não descarta o job reaberto como cancelado")
                .contains(cancelled.id());
    }
}
