package br.com.archflow.api.jobs;

import java.util.Map;

/** One executable job type registered with the worker. */
public interface JobHandler {

    String type();

    void handle(JobContext context, Map<String, Object> payload) throws Exception;
}
