package br.com.archflow.api.jobs;

import java.time.Instant;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.ArrayList;

/** Development repository mirroring the JDBC transition rules. */
public class InMemoryJobRepository implements JobRepository {

    private final Map<String, JobRecord> jobs = new ConcurrentHashMap<>();
    private final Map<String, Instant> leases = new ConcurrentHashMap<>();
    private final Map<String, List<JobAttempt>> attemptHistory = new ConcurrentHashMap<>();

    @Override
    public synchronized JobRecord create(JobRecord job) {
        if (job.idempotencyKey() != null) {
            JobRecord existing = findByIdempotencyKey(
                    job.tenantId(), job.type(), job.idempotencyKey());
            if (existing != null) return existing;
        }
        jobs.put(job.id(), job);
        return job;
    }

    @Override
    public JobRecord find(String tenantId, String id) {
        JobRecord job = jobs.get(id);
        return job != null && job.tenantId().equals(tenantId) ? job : null;
    }

    @Override
    public JobRecord findById(String id) {
        return jobs.get(id);
    }

    @Override
    public JobRecord findByIdempotencyKey(String tenantId, String type, String key) {
        if (key == null) return null;
        return jobs.values().stream()
                .filter(job -> job.tenantId().equals(tenantId))
                .filter(job -> job.type().equals(type))
                .filter(job -> key.equals(job.idempotencyKey()))
                .findFirst().orElse(null);
    }

    @Override
    public List<JobRecord> list(String tenantId, String type) {
        return jobs.values().stream()
                .filter(job -> job.tenantId().equals(tenantId))
                .filter(job -> type == null || type.equals(job.type()))
                .sorted(Comparator.comparing(JobRecord::createdAt).reversed())
                .toList();
    }

    @Override
    public synchronized JobRecord claimNext(
            String workerId, Set<String> supportedTypes, Duration lease) {
        JobRecord queued = jobs.values().stream()
                .filter(job -> job.status() == JobStatus.QUEUED)
                .filter(job -> supportedTypes.isEmpty() || supportedTypes.contains(job.type()))
                .min(Comparator.comparing(JobRecord::createdAt)).orElse(null);
        if (queued == null) return null;
        Instant now = Instant.now();
        JobRecord claimed;
        if (queued.cancelRequested()) {
            claimed = copy(queued, JobStatus.CANCELLED, queued.progress(),
                    "Cancelled", queued.attempt(), null, null, now, null);
        } else {
            claimed = copy(queued, JobStatus.RUNNING, queued.progress(),
                    queued.message(), queued.attempt() + 1, workerId,
                    queued.startedAt() == null ? now : queued.startedAt(), null, null);
        }
        jobs.put(claimed.id(), claimed);
        if (claimed.status() != JobStatus.RUNNING) {
            return claimNext(workerId, supportedTypes, lease);
        }
        leases.put(claimed.id(), now.plus(lease));
        attemptHistory.computeIfAbsent(claimed.id(), ignored -> new ArrayList<>())
                .add(new JobAttempt(claimed.id(), claimed.attempt(), workerId,
                        now, null, null, null));
        return claimed;
    }

    @Override
    public synchronized boolean heartbeat(String id, String workerId, Duration lease) {
        JobRecord job = jobs.get(id);
        if (job == null || job.status() != JobStatus.RUNNING
                || !workerId.equals(job.workerId())) return false;
        leases.put(id, Instant.now().plus(lease));
        return true;
    }

    @Override
    public synchronized int recoverExpiredLeases() {
        Instant now = Instant.now();
        int recovered = 0;
        for (var entry : List.copyOf(leases.entrySet())) {
            if (entry.getValue().isAfter(now)) continue;
            JobRecord job = jobs.get(entry.getKey());
            if (job != null && job.status() == JobStatus.RUNNING) {
                finishAttempt(job, JobStatus.FAILED, "Worker lease expired");
                jobs.put(job.id(), copy(job, JobStatus.QUEUED, job.progress(),
                        "Recovered after worker lease expired", job.attempt(), null,
                        job.startedAt(), null, "Worker lease expired"));
                recovered++;
            }
            leases.remove(entry.getKey());
        }
        return recovered;
    }

    @Override
    public List<JobAttempt> attempts(String tenantId, String jobId) {
        return find(tenantId, jobId) == null
                ? List.of()
                : List.copyOf(attemptHistory.getOrDefault(jobId, List.of()));
    }

    @Override
    public synchronized JobRecord updateProgress(String id, int progress, String message) {
        JobRecord job = jobs.get(id);
        if (job == null || job.status() != JobStatus.RUNNING) return job;
        JobRecord updated = copy(job, job.status(), Math.max(0, Math.min(100, progress)),
                message, job.attempt(), job.workerId(), job.startedAt(), null, job.error());
        jobs.put(id, updated);
        return updated;
    }

    @Override
    public synchronized JobRecord complete(String id) {
        JobRecord job = jobs.get(id);
        if (job == null || job.status() != JobStatus.RUNNING) return job;
        JobStatus status = job.cancelRequested() ? JobStatus.CANCELLED : JobStatus.SUCCEEDED;
        JobRecord updated = copy(job, status, status == JobStatus.SUCCEEDED ? 100 : job.progress(),
                status == JobStatus.SUCCEEDED ? "Completed" : "Cancelled",
                job.attempt(), job.workerId(), job.startedAt(), Instant.now(), null);
        jobs.put(id, updated);
        leases.remove(id);
        finishAttempt(job, status, null);
        return updated;
    }

    @Override
    public synchronized JobRecord fail(String id, String error) {
        JobRecord job = jobs.get(id);
        if (job == null || job.status() != JobStatus.RUNNING) return job;
        boolean retry = !job.cancelRequested() && job.attempt() < job.maxAttempts();
        JobStatus status = job.cancelRequested() ? JobStatus.CANCELLED
                : retry ? JobStatus.QUEUED : JobStatus.FAILED;
        JobRecord updated = copy(job, status, job.progress(),
                retry ? "Queued for retry" : status == JobStatus.CANCELLED
                        ? "Cancelled" : "Failed",
                job.attempt(), retry ? null : job.workerId(), job.startedAt(),
                retry ? null : Instant.now(), error);
        jobs.put(id, updated);
        leases.remove(id);
        finishAttempt(job, status == JobStatus.QUEUED ? JobStatus.FAILED : status, error);
        return updated;
    }

    @Override
    public synchronized JobRecord requestCancel(String tenantId, String id) {
        JobRecord job = find(tenantId, id);
        if (job == null || isTerminal(job.status())) return job;
        JobStatus status = job.status() == JobStatus.QUEUED
                ? JobStatus.CANCELLED : job.status();
        JobRecord updated = copy(job, status, job.progress(), "Cancellation requested",
                job.attempt(), job.workerId(), job.startedAt(),
                status == JobStatus.CANCELLED ? Instant.now() : null, job.error(), true);
        jobs.put(id, updated);
        return updated;
    }

    private static JobRecord copy(JobRecord job, JobStatus status, int progress,
                                  String message, int attempt, String workerId,
                                  Instant startedAt, Instant completedAt, String error) {
        return copy(job, status, progress, message, attempt, workerId,
                startedAt, completedAt, error, job.cancelRequested());
    }

    private static JobRecord copy(JobRecord job, JobStatus status, int progress,
                                  String message, int attempt, String workerId,
                                  Instant startedAt, Instant completedAt, String error,
                                  boolean cancelRequested) {
        return new JobRecord(job.id(), job.tenantId(), job.workspaceId(), job.type(),
                status, job.payload(), progress, message, attempt, job.maxAttempts(),
                job.idempotencyKey(), cancelRequested, workerId, error, job.createdAt(),
                startedAt, completedAt, Instant.now());
    }

    private static boolean isTerminal(JobStatus status) {
        return status == JobStatus.SUCCEEDED || status == JobStatus.FAILED
                || status == JobStatus.CANCELLED;
    }

    private void finishAttempt(JobRecord job, JobStatus outcome, String error) {
        List<JobAttempt> attempts = attemptHistory.get(job.id());
        if (attempts == null || attempts.isEmpty()) return;
        int index = attempts.size() - 1;
        JobAttempt current = attempts.get(index);
        if (current.completedAt() == null) {
            attempts.set(index, new JobAttempt(current.jobId(), current.attempt(),
                    current.workerId(), current.startedAt(), Instant.now(), outcome, error));
        }
    }
}
