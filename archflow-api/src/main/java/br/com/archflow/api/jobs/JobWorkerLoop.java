package br.com.archflow.api.jobs;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Fixed-delay polling loop with failures contained between iterations. */
public class JobWorkerLoop implements AutoCloseable {

    private final JobWorker worker;
    private final java.util.concurrent.ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofVirtual().name("archflow-job-worker-", 0).factory());

    public JobWorkerLoop(JobWorker worker, Duration pollInterval) {
        this.worker = worker;
        executor.scheduleWithFixedDelay(this::pollSafely, 0,
                pollInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void pollSafely() {
        try {
            worker.runOnce();
        } catch (RuntimeException ignored) {
            // The durable job remains claimable/recoverable; the next poll continues.
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
        worker.close();
    }
}
