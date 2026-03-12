package br.com.archflow.api.conversation.dto;

/**
 * Request DTO for suspending a conversation.
 */
public record SuspendRequest(
        String conversationId,
        String workflowId,
        String executionId,
        String formId,
        Integer timeoutMinutes
) {
}
