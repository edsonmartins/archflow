package br.com.archflow.api.workflow.dto;

import java.util.List;

/**
 * Projection of {@link br.com.archflow.agent.governance.GovernanceProfile}
 * for the workflow editor. Frontends use this to offer pre-configured
 * profiles in the governance accordion, and to seed the "Custom"
 * profile fields when a user picks a starting point.
 */
public record GovernanceProfileDto(
        String id,
        String name,
        String systemPrompt,
        List<String> enabledTools,
        List<String> disabledTools,
        double escalationThreshold,
        int maxToolExecutions,
        String customInstructions
) {}
