package br.com.archflow.brainsentry;

import br.com.archflow.conversation.memory.Episode;
import br.com.archflow.conversation.memory.EpisodicMemory;
import br.com.archflow.conversation.memory.ScoredEpisode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;

/**
 * Adapts Brain Sentry as a backend for archflow's EpisodicMemory interface.
 *
 * <p>Maps archflow Episodes to Brain Sentry Memories:
 * <ul>
 *   <li>{@code store()} → POST /v1/memories</li>
 *   <li>{@code recall()} → POST /v1/memories/search (hybrid search)</li>
 *   <li>{@code getByContext()} → GET /v1/memories (filtered by tenant)</li>
 * </ul>
 *
 * <p>This gives archflow agents access to Brain Sentry's hybrid search
 * (vector + BM25 + graph), PII protection, and cross-session memory.
 */
public class BrainSentryMemoryAdapter implements EpisodicMemory {

    private static final Logger log = LoggerFactory.getLogger(BrainSentryMemoryAdapter.class);

    private final BrainSentryClient client;

    public BrainSentryMemoryAdapter(BrainSentryClient client) {
        this.client = Objects.requireNonNull(client);
    }

    @Override
    public void store(Episode episode) {
        try {
            String category = mapEpisodeType(episode.type());
            String importance = episode.importance() >= 0.7 ? "CRITICAL"
                    : episode.importance() >= 0.4 ? "IMPORTANT" : "MINOR";
            List<String> tags = new ArrayList<>();
            tags.add("archflow");
            tags.add("context:" + episode.contextId());

            client.createMemory(episode.content(), category, importance, "EPISODIC", tags);
            log.debug("Stored episode {} to Brain Sentry", episode.id());
        } catch (Exception e) {
            log.error("Failed to store episode to Brain Sentry: {}", e.getMessage());
        }
    }

    @Override
    public List<ScoredEpisode> recall(String query, String contextId, int maxResults) {
        try {
            List<Memory> memories = client.searchMemories(query, maxResults);
            return memories.stream()
                    .map(m -> toScoredEpisode(m, contextId))
                    .toList();
        } catch (Exception e) {
            log.error("Failed to recall from Brain Sentry: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<Episode> getByContext(String contextId) {
        try {
            List<Memory> memories = client.searchMemories("context:" + contextId, 50);
            return memories.stream()
                    .map(m -> toEpisode(m, contextId))
                    .sorted(Comparator.comparing(Episode::timestamp).reversed())
                    .toList();
        } catch (Exception e) {
            log.error("Failed to get by context from Brain Sentry: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public Optional<Episode> getById(String episodeId) {
        try {
            return client.getMemory(episodeId).map(m -> toEpisode(m, "unknown"));
        } catch (Exception e) {
            log.error("Failed to get by ID from Brain Sentry: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public int evict(String contextId, int maxEpisodes) {
        // Brain Sentry handles decay/eviction internally
        log.debug("Eviction delegated to Brain Sentry's internal decay mechanism");
        return 0;
    }

    @Override
    public void clear(String contextId) {
        log.warn("Clear not supported via Brain Sentry REST API — use Brain Sentry dashboard");
    }

    @Override
    public int size() {
        // Brain Sentry doesn't expose a count endpoint; return -1 to indicate unknown
        return -1;
    }

    // --- Mapping helpers ---

    private String mapEpisodeType(Episode.EpisodeType type) {
        return switch (type) {
            case INTERACTION -> "CONTEXT";
            case ACTION -> "ACTION";
            case OUTCOME -> "INSIGHT";
            case ERROR -> "WARNING";
            case FEEDBACK -> "KNOWLEDGE";
        };
    }

    private ScoredEpisode toScoredEpisode(Memory memory, String contextId) {
        Episode episode = toEpisode(memory, contextId);
        double score = mapImportance(memory.importance());
        return new ScoredEpisode(episode, score, score, 1.0, score);
    }

    private Episode toEpisode(Memory memory, String contextId) {
        double importance = mapImportance(memory.importance());
        Map<String, String> metadata = new HashMap<>();
        metadata.put("brainsentry.id", memory.id());
        metadata.put("brainsentry.category", memory.category() != null ? memory.category() : "");
        metadata.put("brainsentry.memoryType", memory.memoryType() != null ? memory.memoryType() : "");

        return new Episode(
                memory.id(),
                contextId,
                memory.content(),
                memory.summary(),
                Episode.EpisodeType.INTERACTION,
                importance,
                metadata,
                memory.createdAt() != null ? memory.createdAt() : Instant.now()
        );
    }

    private double mapImportance(String importance) {
        if (importance == null) return 0.5;
        return switch (importance.toUpperCase()) {
            case "CRITICAL" -> 0.9;
            case "IMPORTANT" -> 0.6;
            case "MINOR" -> 0.3;
            default -> 0.5;
        };
    }
}
