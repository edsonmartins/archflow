package br.com.archflow.api.web.workflow;

import java.time.Instant;
import java.util.Map;

/** Immutable snapshot of a workflow document created at publication time. */
public record WorkflowVersionRecord(
        String id,
        String workflowId,
        int number,
        Map<String, Object> document,
        String comment,
        Instant createdAt) {
}
