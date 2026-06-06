package br.com.archflow.api.orchestration;

import java.util.List;

/** Result of a dynamic workflow run. */
public record DynamicWorkflowResponse(List<Object> confirmed, int confirmedCount, int rounds) {
}
