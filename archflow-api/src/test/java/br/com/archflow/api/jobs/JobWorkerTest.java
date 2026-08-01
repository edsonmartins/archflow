package br.com.archflow.api.jobs;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JobWorkerTest {

    @Test
    void dispatchesOnlyRegisteredTypesAndRecordsAttempt() {
        var repository = new InMemoryJobRepository();
        var service = new JobService(repository);
        JobRecord unknown = service.submit(
                "tenant", null, "UNKNOWN", Map.of(), null, 3);
        JobRecord known = service.submit(
                "tenant", null, "DOCUMENT_INGESTION", Map.of("fileId", "f1"), null, 3);
        var handler = new RecordingHandler();

        try (var worker = new JobWorker("worker-1", service,
                new JobHandlerRegistry(List.of(handler)), Duration.ofSeconds(5))) {
            assertThat(worker.runOnce()).isTrue();
        }

        assertThat(handler.seenFile).isEqualTo("f1");
        assertThat(service.get("tenant", known.id()).status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(service.get("tenant", unknown.id()).status()).isEqualTo(JobStatus.QUEUED);
        assertThat(service.attempts("tenant", known.id())).singleElement().satisfies(attempt -> {
            assertThat(attempt.workerId()).isEqualTo("worker-1");
            assertThat(attempt.outcome()).isEqualTo(JobStatus.SUCCEEDED);
            assertThat(attempt.completedAt()).isNotNull();
        });
    }

    @Test
    void expiredLeaseRequeuesJobAndClosesAttempt() {
        var repository = new InMemoryJobRepository();
        var service = new JobService(repository);
        JobRecord job = service.submit("tenant", null, "TYPE", Map.of(), null, 3);
        service.claim("dead-worker", java.util.Set.of("TYPE"), Duration.ZERO);

        assertThat(service.recoverExpiredLeases()).isEqualTo(1);

        assertThat(service.get("tenant", job.id()).status()).isEqualTo(JobStatus.QUEUED);
        assertThat(service.attempts("tenant", job.id()))
                .singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt.outcome()).isEqualTo(JobStatus.FAILED);
                    assertThat(attempt.error()).contains("lease expired");
                });
    }

    private static final class RecordingHandler implements JobHandler {
        private String seenFile;

        public String type() {
            return "DOCUMENT_INGESTION";
        }

        public void handle(JobContext context, Map<String, Object> payload) {
            context.progress(50, "Parsing");
            seenFile = String.valueOf(payload.get("fileId"));
        }
    }
}
