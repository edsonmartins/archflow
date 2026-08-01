package br.com.archflow.api.jobs;

/** Cooperative progress and cancellation channel exposed to handlers. */
public interface JobContext {

    String jobId();

    void progress(int percentage, String message);

    boolean isCancellationRequested();

    default void checkCancelled() {
        if (isCancellationRequested()) {
            throw new JobCancelledException();
        }
    }
}
