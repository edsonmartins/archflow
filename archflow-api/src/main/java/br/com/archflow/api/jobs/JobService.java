package br.com.archflow.api.jobs;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Duration;
import java.util.Set;

/** Tenant-facing job lifecycle facade. Workers use claim/progress/complete/fail. */
public class JobService {

    private final JobRepository repository;

    public JobService(JobRepository repository) {
        this.repository = repository;
    }

    public JobRecord submit(String tenantId, String workspaceId, String type,
                            Map<String, Object> payload, String idempotencyKey,
                            int maxAttempts) {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("type cannot be blank");
        if (maxAttempts < 1 || maxAttempts > 20) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 20");
        }
        Instant now = Instant.now();
        return repository.create(new JobRecord(
                "job-" + UUID.randomUUID(), tenantId, blankToNull(workspaceId),
                type.trim(), JobStatus.QUEUED, payload == null ? Map.of() : Map.copyOf(payload),
                0, "Queued", 0, maxAttempts, blankToNull(idempotencyKey), false,
                null, null, now, null, null, now));
    }

    public JobRecord get(String tenantId, String id) {
        return repository.find(tenantId, id);
    }

    public List<JobRecord> list(String tenantId, String type) {
        return repository.list(tenantId, blankToNull(type));
    }

    public JobRecord cancel(String tenantId, String id) {
        return repository.requestCancel(tenantId, id);
    }

    public JobRecord claim(String workerId) {
        return repository.claimNext(workerId);
    }

    public JobRecord claim(String workerId, Set<String> supportedTypes, Duration lease) {
        return repository.claimNext(workerId, supportedTypes, lease);
    }

    public boolean heartbeat(String id, String workerId, Duration lease) {
        return repository.heartbeat(id, workerId, lease);
    }

    public int recoverExpiredLeases() {
        return repository.recoverExpiredLeases();
    }

    public List<JobAttempt> attempts(String tenantId, String jobId) {
        return repository.attempts(tenantId, jobId);
    }

    JobRecord getByIdForWorker(String id) {
        return repository.findById(id);
    }

    public JobRecord progress(String id, int progress, String message) {
        return repository.updateProgress(id, progress, message);
    }

    public JobRecord complete(String id) {
        return repository.complete(id);
    }

    public JobRecord fail(String id, String error) {
        return repository.fail(id, error);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
