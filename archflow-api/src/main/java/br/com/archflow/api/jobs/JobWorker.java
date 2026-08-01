package br.com.archflow.api.jobs;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Executes one claimed job at a time; scheduling can call {@link #runOnce()}. */
public class JobWorker implements AutoCloseable {

    private final String workerId;
    private final JobService service;
    private final JobHandlerRegistry registry;
    private final Duration lease;
    private final java.util.concurrent.ScheduledExecutorService heartbeatExecutor =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofVirtual().name("archflow-job-heartbeat-", 0).factory());

    public JobWorker(String workerId, JobService service,
                     JobHandlerRegistry registry, Duration lease) {
        this.workerId = Objects.requireNonNull(workerId);
        this.service = service;
        this.registry = registry;
        this.lease = lease;
    }

    public boolean runOnce() {
        service.recoverExpiredLeases();
        JobRecord job = service.claim(workerId, registry.types(), lease);
        if (job == null) return false;
        JobHandler handler = registry.get(job.type());
        long heartbeatMillis = Math.max(100, lease.toMillis() / 3);
        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(
                () -> service.heartbeat(job.id(), workerId, lease),
                heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS);
        try {
            handler.handle(new Context(job.id()), job.payload());
            service.complete(job.id());
        } catch (JobCancelledException cancelled) {
            service.fail(job.id(), cancelled.getMessage());
        } catch (Exception failure) {
            service.fail(job.id(), failure.getMessage() == null
                    ? failure.toString() : failure.getMessage());
        } finally {
            heartbeat.cancel(false);
        }
        return true;
    }

    @Override
    public void close() {
        heartbeatExecutor.shutdownNow();
    }

    private final class Context implements JobContext {
        private final String jobId;

        private Context(String jobId) {
            this.jobId = jobId;
        }

        public String jobId() {
            return jobId;
        }

        public void progress(int percentage, String message) {
            service.progress(jobId, percentage, message);
        }

        public boolean isCancellationRequested() {
            JobRecord current = service.getByIdForWorker(jobId);
            return current == null || current.cancelRequested();
        }
    }
}
