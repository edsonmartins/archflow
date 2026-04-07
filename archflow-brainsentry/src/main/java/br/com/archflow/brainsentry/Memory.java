package br.com.archflow.brainsentry;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Represents a Brain Sentry memory entry.
 *
 * @param id Memory UUID
 * @param content Full memory content
 * @param summary Brief summary
 * @param category Memory category (INSIGHT, DECISION, WARNING, KNOWLEDGE, etc.)
 * @param importance Importance level (CRITICAL, IMPORTANT, MINOR)
 * @param memoryType Type (SEMANTIC, EPISODIC, PROCEDURAL, ASSOCIATIVE, etc.)
 * @param tags Custom tags
 * @param metadata Arbitrary key-value metadata
 * @param createdAt Creation timestamp
 */
public record Memory(
        String id,
        String content,
        String summary,
        String category,
        String importance,
        String memoryType,
        List<String> tags,
        Map<String, Object> metadata,
        Instant createdAt
) {
    public Memory {
        tags = tags == null ? List.of() : List.copyOf(tags);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
