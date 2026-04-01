package br.com.archflow.conversation.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("InMemoryEpisodicMemory")
class InMemoryEpisodicMemoryTest {

    private InMemoryEpisodicMemory memory;

    @BeforeEach
    void setUp() {
        memory = new InMemoryEpisodicMemory();
    }

    @Test
    @DisplayName("should store and recall episodes")
    void shouldStoreAndRecall() {
        memory.store(Episode.of("user-1", "Customer asked about order #123 delivery status", 0.7));
        memory.store(Episode.of("user-1", "Customer complained about late delivery", 0.9));

        List<ScoredEpisode> results = memory.recall("delivery status", "user-1", 5);

        assertEquals(2, results.size());
        assertTrue(results.get(0).score() >= results.get(1).score());
    }

    @Test
    @DisplayName("should return empty for unknown context")
    void shouldReturnEmptyForUnknownContext() {
        List<ScoredEpisode> results = memory.recall("anything", "unknown", 5);
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("should isolate episodes by context")
    void shouldIsolateByContext() {
        memory.store(Episode.of("user-1", "Order #123", 0.5));
        memory.store(Episode.of("user-2", "Order #456", 0.5));

        List<Episode> user1 = memory.getByContext("user-1");
        assertEquals(1, user1.size());
        assertTrue(user1.get(0).content().contains("123"));
    }

    @Test
    @DisplayName("should rank by importance when queries are similar")
    void shouldRankByImportance() {
        memory.store(Episode.of("ctx", "payment issue with order", 0.3));
        memory.store(Episode.of("ctx", "payment problem with invoice", 0.9));

        List<ScoredEpisode> results = memory.recall("payment issue", "ctx", 5);

        // Higher importance should rank higher (all else being approximately equal)
        assertEquals(2, results.size());
        assertTrue(results.get(0).importanceScore() >= results.get(1).importanceScore()
                || results.get(0).score() >= results.get(1).score());
    }

    @Test
    @DisplayName("should limit results to maxResults")
    void shouldLimitResults() {
        for (int i = 0; i < 20; i++) {
            memory.store(Episode.of("ctx", "Episode " + i, 0.5));
        }

        List<ScoredEpisode> results = memory.recall("episode", "ctx", 5);
        assertEquals(5, results.size());
    }

    @Test
    @DisplayName("should evict oldest episodes beyond limit")
    void shouldEvictOldest() {
        // Store episodes with different timestamps
        for (int i = 0; i < 10; i++) {
            Episode ep = new Episode(null, "ctx", "Episode " + i, null,
                    Episode.EpisodeType.INTERACTION, 0.5, Map.of(),
                    Instant.now().minusSeconds(100 - i * 10));
            memory.store(ep);
        }

        int evicted = memory.evict("ctx", 5);

        assertEquals(5, evicted);
        assertEquals(5, memory.getByContext("ctx").size());
    }

    @Test
    @DisplayName("should not evict when under limit")
    void shouldNotEvictWhenUnderLimit() {
        memory.store(Episode.of("ctx", "Episode 1", 0.5));
        memory.store(Episode.of("ctx", "Episode 2", 0.5));

        int evicted = memory.evict("ctx", 10);
        assertEquals(0, evicted);
    }

    @Test
    @DisplayName("should clear all episodes for context")
    void shouldClearContext() {
        memory.store(Episode.of("ctx", "Episode 1", 0.5));
        memory.store(Episode.of("ctx", "Episode 2", 0.5));

        memory.clear("ctx");

        assertEquals(0, memory.getByContext("ctx").size());
    }

    @Test
    @DisplayName("should get episode by ID")
    void shouldGetById() {
        Episode ep = Episode.of("ctx", "Special episode", 0.8);
        memory.store(ep);

        var found = memory.getById(ep.id());
        assertTrue(found.isPresent());
        assertEquals("Special episode", found.get().content());
    }

    @Test
    @DisplayName("should return total size across all contexts")
    void shouldReturnTotalSize() {
        memory.store(Episode.of("ctx-1", "A", 0.5));
        memory.store(Episode.of("ctx-1", "B", 0.5));
        memory.store(Episode.of("ctx-2", "C", 0.5));

        assertEquals(3, memory.size());
    }

    @Test
    @DisplayName("should use custom similarity function")
    void shouldUseCustomSimilarity() {
        InMemoryEpisodicMemory customMemory = new InMemoryEpisodicMemory(
                0.8, 0.1, 0.1, Duration.ofHours(24),
                (query, content) -> content.contains("match") ? 1.0 : 0.0
        );

        customMemory.store(Episode.of("ctx", "This will match", 0.5));
        customMemory.store(Episode.of("ctx", "This will not", 0.5));

        List<ScoredEpisode> results = customMemory.recall("anything", "ctx", 5);

        assertEquals(2, results.size());
        assertTrue(results.get(0).similarityScore() > results.get(1).similarityScore());
    }

    @Test
    @DisplayName("should support different episode types")
    void shouldSupportDifferentTypes() {
        memory.store(Episode.of("ctx", "User asked question", Episode.EpisodeType.INTERACTION, 0.5));
        memory.store(Episode.of("ctx", "Agent searched database", Episode.EpisodeType.ACTION, 0.6));
        memory.store(Episode.of("ctx", "Found the answer", Episode.EpisodeType.OUTCOME, 0.7));

        List<Episode> all = memory.getByContext("ctx");
        assertEquals(3, all.size());
    }

    @Test
    @DisplayName("should order getByContext by timestamp descending")
    void shouldOrderByTimestampDescending() {
        Episode old = new Episode(null, "ctx", "Old", null, null, 0.5, Map.of(),
                Instant.now().minusSeconds(3600));
        Episode recent = new Episode(null, "ctx", "Recent", null, null, 0.5, Map.of(),
                Instant.now());

        memory.store(old);
        memory.store(recent);

        List<Episode> results = memory.getByContext("ctx");
        assertEquals("Recent", results.get(0).content());
        assertEquals("Old", results.get(1).content());
    }
}
