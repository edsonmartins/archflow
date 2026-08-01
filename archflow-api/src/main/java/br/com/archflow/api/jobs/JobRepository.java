package br.com.archflow.api.jobs;

import java.util.List;
import java.time.Duration;
import java.util.Set;

/** Persistence contract whose mutations return the resulting durable snapshot. */
public interface JobRepository {

    JobRecord create(JobRecord job);

    JobRecord find(String tenantId, String id);

    /** Internal worker lookup; never expose this without a tenant check. */
    JobRecord findById(String id);

    JobRecord findByIdempotencyKey(String tenantId, String type, String key);

    List<JobRecord> list(String tenantId, String type);

    JobRecord claimNext(String workerId, Set<String> supportedTypes, Duration lease);

    default JobRecord claimNext(String workerId) {
        return claimNext(workerId, Set.of(), Duration.ofSeconds(30));
    }

    boolean heartbeat(String id, String workerId, Duration lease);

    int recoverExpiredLeases();

    List<JobAttempt> attempts(String tenantId, String jobId);

    JobRecord updateProgress(String id, int progress, String message);

    JobRecord complete(String id);

    JobRecord fail(String id, String error);

    JobRecord requestCancel(String tenantId, String id);
}
