package br.com.archflow.conversation.memory;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A single episode (interaction record) stored in episodic memory.
 *
 * <p>Contains the interaction content, metadata, and scoring attributes.
 */
public record Episode(
        String id,
        String tenantId,
        String contextId,
        String content,
        String summary,
        EpisodeType type,
        double importance,
        Map<String, String> metadata,
        Instant timestamp
) {
    public Episode {
        Objects.requireNonNull(contextId, "contextId required");
        Objects.requireNonNull(content, "content required");
        if (id == null) id = UUID.randomUUID().toString();
        if (tenantId == null) tenantId = "SYSTEM";
        if (type == null) type = EpisodeType.INTERACTION;
        if (importance < 0 || importance > 1) throw new IllegalArgumentException("importance must be 0..1");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        if (timestamp == null) timestamp = Instant.now();
    }

    public static Episode of(String contextId, String content, double importance) {
        return new Episode(null, "SYSTEM", contextId, content, null, EpisodeType.INTERACTION, importance, Map.of(), null);
    }

    public static Episode of(String contextId, String content, EpisodeType type, double importance) {
        return new Episode(null, "SYSTEM", contextId, content, null, type, importance, Map.of(), null);
    }

    public static Episode of(String contextId, String content, double importance, Map<String, String> metadata) {
        return new Episode(null, "SYSTEM", contextId, content, null, EpisodeType.INTERACTION, importance, metadata, null);
    }

    public static Episode of(String tenantId, String contextId, String content, EpisodeType type, double importance) {
        return new Episode(null, tenantId, contextId, content, null, type, importance, Map.of(), null);
    }

    public static Episode of(String tenantId, String contextId, String content, double importance, Map<String, String> metadata) {
        return new Episode(null, tenantId, contextId, content, null, EpisodeType.INTERACTION, importance, metadata, null);
    }

    /**
     * Types of episodes.
     */
    public enum EpisodeType {
        /** User-agent interaction */
        INTERACTION,
        /** Agent decision or action */
        ACTION,
        /** Outcome or result */
        OUTCOME,
        /** Error or failure */
        ERROR,
        /** Feedback received */
        FEEDBACK
    }
}
