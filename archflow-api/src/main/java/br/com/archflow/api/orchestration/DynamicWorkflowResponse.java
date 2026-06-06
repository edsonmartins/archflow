package br.com.archflow.api.orchestration;

import br.com.archflow.agent.orchestration.OrchestrationTrace;

import java.util.List;

/** Result of a dynamic workflow run, including a progress trace (design-0004 step 3). */
public record DynamicWorkflowResponse(
        List<Object> confirmed,
        int confirmedCount,
        int rounds,
        List<OrchestrationTrace.Entry> trace) {
}
