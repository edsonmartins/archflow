package br.com.archflow.api.conversation.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Response DTO for conversation details.
 */
public record ConversationResponse(
        String conversationId,
        String status,
        String resumeToken,
        Object form,
        Instant expiresAt,
        List<MessageResponse> messages
) {

    /**
     * Represents a single message in the conversation response.
     */
    public record MessageResponse(
            String id,
            String role,
            String content,
            Instant timestamp,
            Map<String, Object> metadata
    ) {
    }
}
