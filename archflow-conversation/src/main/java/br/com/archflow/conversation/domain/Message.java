package br.com.archflow.conversation.domain;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A single message in a {@link Conversation}.
 *
 * @param id              Unique message identifier
 * @param conversationId  Foreign key to {@link Conversation#id()}
 * @param tenantId        Tenant (denormalized for query convenience)
 * @param senderType      Who sent the message
 * @param messageType     Type of message
 * @param content         Text content (or transcription for audio/image)
 * @param mediaUrl        Optional URL to media (audio/image/document)
 * @param metadata        Free-form metadata (sentiment, entities, tool results, etc.)
 * @param timestamp       When the message was created
 */
public record Message(
        String id,
        String conversationId,
        String tenantId,
        SenderType senderType,
        MessageType messageType,
        String content,
        String mediaUrl,
        Map<String, Object> metadata,
        Instant timestamp
) {
    public Message {
        Objects.requireNonNull(conversationId, "conversationId is required");
        Objects.requireNonNull(tenantId, "tenantId is required");
        Objects.requireNonNull(senderType, "senderType is required");
        Objects.requireNonNull(content, "content is required");
        if (id == null) id = UUID.randomUUID().toString();
        if (messageType == null) messageType = MessageType.TEXT;
        if (metadata == null) metadata = new HashMap<>();
        if (timestamp == null) timestamp = Instant.now();
    }

    public static Message userText(String conversationId, String tenantId, String text) {
        return new Message(null, conversationId, tenantId, SenderType.USER, MessageType.TEXT, text, null, null, null);
    }

    public static Message agentText(String conversationId, String tenantId, String text) {
        return new Message(null, conversationId, tenantId, SenderType.AGENT, MessageType.TEXT, text, null, null, null);
    }

    public static Message systemText(String conversationId, String tenantId, String text) {
        return new Message(null, conversationId, tenantId, SenderType.SYSTEM, MessageType.TEXT, text, null, null, null);
    }

    public enum SenderType {
        USER,
        AGENT,
        SYSTEM,
        HUMAN_OPERATOR
    }

    public enum MessageType {
        TEXT,
        AUDIO,
        IMAGE,
        DOCUMENT,
        VIDEO,
        LOCATION,
        TOOL_RESULT
    }
}
