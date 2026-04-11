package br.com.archflow.conversation.domain;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain entity representing a conversation between a user and an agent.
 *
 * <p>Mirrors the SAC agent's {@code ConversationEntity} but as an immutable
 * record. Persistence is delegated to {@link ConversationRepository}
 * implementations — products choose JPA, MongoDB, in-memory, etc.
 *
 * @param id          Unique conversation identifier
 * @param tenantId    Owning tenant
 * @param userId      End user identifier (e.g., phone number, account id)
 * @param channel     Communication channel (WHATSAPP, WEB, API, etc.)
 * @param status      Current status
 * @param persona     Active persona (e.g., "ORDER_TRACKING", "CUSTOMER_SUPPORT")
 * @param metadata    Free-form key-value metadata
 * @param createdAt   Creation timestamp
 * @param updatedAt   Last update timestamp
 */
public record Conversation(
        String id,
        String tenantId,
        String userId,
        String channel,
        ConversationStatus status,
        String persona,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt
) {
    public Conversation {
        Objects.requireNonNull(tenantId, "tenantId is required");
        Objects.requireNonNull(userId, "userId is required");
        if (id == null) id = UUID.randomUUID().toString();
        if (channel == null) channel = "API";
        if (status == null) status = ConversationStatus.ACTIVE;
        if (metadata == null) metadata = new HashMap<>();
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = createdAt;
    }

    public static Conversation start(String tenantId, String userId, String channel) {
        return new Conversation(null, tenantId, userId, channel, ConversationStatus.ACTIVE,
                null, new HashMap<>(), null, null);
    }

    public Conversation withStatus(ConversationStatus newStatus) {
        return new Conversation(id, tenantId, userId, channel, newStatus, persona, metadata, createdAt, Instant.now());
    }

    public Conversation withPersona(String newPersona) {
        return new Conversation(id, tenantId, userId, channel, status, newPersona, metadata, createdAt, Instant.now());
    }

    public Conversation withMetadata(String key, Object value) {
        Map<String, Object> newMeta = new HashMap<>(metadata);
        newMeta.put(key, value);
        return new Conversation(id, tenantId, userId, channel, status, persona, newMeta, createdAt, Instant.now());
    }

    public enum ConversationStatus {
        ACTIVE,
        AWAITING_HUMAN,
        SUSPENDED,
        CLOSED,
        ESCALATED
    }
}
