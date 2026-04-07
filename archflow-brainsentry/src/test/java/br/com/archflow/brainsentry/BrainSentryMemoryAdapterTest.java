package br.com.archflow.brainsentry;

import br.com.archflow.conversation.memory.Episode;
import br.com.archflow.conversation.memory.ScoredEpisode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("BrainSentryMemoryAdapter")
class BrainSentryMemoryAdapterTest {

    private BrainSentryClient client;
    private BrainSentryMemoryAdapter adapter;

    @BeforeEach
    void setUp() {
        client = mock(BrainSentryClient.class);
        adapter = new BrainSentryMemoryAdapter(client);
    }

    @Test @DisplayName("should store episode via client")
    void shouldStore() throws Exception {
        var episode = Episode.of("user-1", "Customer asked about order #123", 0.8);

        adapter.store(episode);

        verify(client).createMemory(eq("Customer asked about order #123"),
                eq("CONTEXT"), eq("CRITICAL"), eq("EPISODIC"), anyList());
    }

    @Test @DisplayName("should map importance correctly")
    void shouldMapImportance() throws Exception {
        adapter.store(Episode.of("u", "Low importance", 0.2));
        verify(client).createMemory(any(), any(), eq("MINOR"), any(), any());

        adapter.store(Episode.of("u", "Medium importance", 0.5));
        verify(client).createMemory(any(), any(), eq("IMPORTANT"), any(), any());
    }

    @Test @DisplayName("should recall via search")
    void shouldRecall() throws Exception {
        var memory = new Memory("m1", "Found content", "summary", "KNOWLEDGE",
                "CRITICAL", "SEMANTIC", List.of("tag"), Map.of(), Instant.now());
        when(client.searchMemories("order status", 5)).thenReturn(List.of(memory));

        List<ScoredEpisode> results = adapter.recall("order status", "user-1", 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).episode().content()).isEqualTo("Found content");
    }

    @Test @DisplayName("should get by ID")
    void shouldGetById() throws Exception {
        var memory = new Memory("m1", "Content", null, "KNOWLEDGE",
                "IMPORTANT", "SEMANTIC", List.of(), Map.of(), Instant.now());
        when(client.getMemory("m1")).thenReturn(Optional.of(memory));

        var result = adapter.getById("m1");

        assertThat(result).isPresent();
        assertThat(result.get().content()).isEqualTo("Content");
    }

    @Test @DisplayName("should return empty when not found")
    void shouldReturnEmpty() throws Exception {
        when(client.getMemory("unknown")).thenReturn(Optional.empty());

        assertThat(adapter.getById("unknown")).isEmpty();
    }

    @Test @DisplayName("should handle client errors gracefully")
    void shouldHandleErrors() throws Exception {
        when(client.searchMemories(any(), anyInt())).thenThrow(new RuntimeException("Network"));

        List<ScoredEpisode> results = adapter.recall("query", "ctx", 5);

        assertThat(results).isEmpty(); // Graceful degradation
    }

    @Test @DisplayName("should delegate eviction to Brain Sentry")
    void shouldDelegateEviction() {
        int evicted = adapter.evict("user-1", 10);
        assertThat(evicted).isZero(); // Brain Sentry handles internally
    }

    @Test @DisplayName("should return -1 for size (unknown)")
    void shouldReturnUnknownSize() {
        assertThat(adapter.size()).isEqualTo(-1);
    }
}
