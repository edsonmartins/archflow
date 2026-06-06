package br.com.archflow.api.orchestration;

/**
 * Request to fire a dynamic workflow (ADR-0002 / design-0003). Only {@code goal}
 * is required; the rest fall back to sensible defaults in the service.
 *
 * @param goal            the objective to decompose and pursue
 * @param decomposePrompt how to decompose it (optional; a generic default is used)
 * @param maxSubtasks     cap on subtasks per planning round (default 8)
 * @param voters          adversarial verifiers per finding (default 1)
 * @param minAgree        verifiers that must NOT refute to confirm (default majority)
 * @param maxRounds       max loop-until-dry rounds (default 5)
 * @param concurrency     parallel fan-out width (default 4)
 * @param budgetTokens    token ceiling for the whole run (optional; unlimited if null)
 * @param tenantId        tenant for model/key resolution (optional)
 */
public record DynamicWorkflowRequest(
        String goal,
        String decomposePrompt,
        Integer maxSubtasks,
        Integer voters,
        Integer minAgree,
        Integer maxRounds,
        Integer concurrency,
        Long budgetTokens,
        String tenantId
) {
}
