package br.com.archflow.api.web.workflow;

import java.time.Instant;

/** Current workflow version selected for an execution environment. */
public record WorkflowDeploymentRecord(
        String workflowId,
        String environment,
        String versionId,
        Instant deployedAt) {
}
