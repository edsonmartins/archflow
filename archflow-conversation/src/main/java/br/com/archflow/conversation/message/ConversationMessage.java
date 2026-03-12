package br.com.archflow.conversation.message;

import java.time.Instant;
import java.util.Map;

/**
 * Represents a single message in a conversation.
 */
public record ConversationMessage(
        String id,
        String conversationId,
        Role role,
        String content,
        Instant timestamp,
        Map<String, Object> metadata
) {
    public enum Role {
        USER, ASSISTANT, SYSTEM, TOOL
    }

    public static ConversationMessage user(String conversationId, String content) {
        return new ConversationMessage(
                java.util.UUID.randomUUID().toString(),
                conversationId, Role.USER, content, Instant.now(), Map.of());
    }

    public static ConversationMessage assistant(String conversationId, String content) {
        return new ConversationMessage(
                java.util.UUID.randomUUID().toString(),
                conversationId, Role.ASSISTANT, content, Instant.now(), Map.of());
    }

    public static ConversationMessage system(String conversationId, String content) {
        return new ConversationMessage(
                java.util.UUID.randomUUID().toString(),
                conversationId, Role.SYSTEM, content, Instant.now(), Map.of());
    }

    public static ConversationMessage tool(String conversationId, String content, Map<String, Object> metadata) {
        return new ConversationMessage(
                java.util.UUID.randomUUID().toString(),
                conversationId, Role.TOOL, content, Instant.now(), metadata);
    }
}
