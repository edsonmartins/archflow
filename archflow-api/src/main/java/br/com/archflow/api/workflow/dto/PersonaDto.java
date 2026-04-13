package br.com.archflow.api.workflow.dto;

/**
 * Projection of {@link br.com.archflow.conversation.persona.Persona}
 * for the workflow editor. Drops the regex keyword patterns (which are
 * compiled {@link java.util.regex.Pattern} instances, not serializable)
 * and the allowedTools list (surfaced separately).
 */
public record PersonaDto(
        String id,
        String label,
        String description,
        String promptId
) {}
