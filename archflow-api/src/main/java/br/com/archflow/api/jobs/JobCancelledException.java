package br.com.archflow.api.jobs;

public class JobCancelledException extends RuntimeException {
    public JobCancelledException() {
        super("Job cancellation requested");
    }
}
