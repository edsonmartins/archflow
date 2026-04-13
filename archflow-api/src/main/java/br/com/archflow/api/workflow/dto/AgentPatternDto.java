package br.com.archflow.api.workflow.dto;

/**
 * Metadata for an agent reasoning strategy that the workflow editor
 * exposes in its "Execution strategy" selector.
 *
 * <p>These are not runtime-configurable on the backend — the patterns
 * ({@code react}, {@code plan-execute}, {@code rewoo},
 * {@code chain-of-thought}) are separate classes. The DTO just tells
 * the editor which options to show and how to label them.
 */
public record AgentPatternDto(
        String id,
        String label,
        String description
) {}
