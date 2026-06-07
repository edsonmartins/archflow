package br.com.archflow.agent.orchestration;

import br.com.archflow.orchestration.ConvergePolicy;
import br.com.archflow.orchestration.VerifyPolicy;

/**
 * Configuration for a {@link DynamicSupervisor} run: how to decompose, how many
 * subtasks per round, how to verify each finding, and when to converge.
 */
public record SupervisorConfig(String decomposePrompt,
                               int maxSubtasks,
                               VerifyPolicy verifyPolicy,
                               ConvergePolicy convergePolicy) {
}
