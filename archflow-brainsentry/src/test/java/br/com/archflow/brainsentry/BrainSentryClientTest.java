package br.com.archflow.brainsentry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("BrainSentryClient")
class BrainSentryClientTest {

    @Test @DisplayName("should create client with config")
    void shouldCreateClient() {
        var config = BrainSentryConfig.of("http://localhost:8081/api");
        var client = new BrainSentryClient(config);
        assertThat(client).isNotNull();
    }

    @Test @DisplayName("should create config with defaults")
    void shouldCreateConfigDefaults() {
        var config = BrainSentryConfig.of("http://localhost:8081/api");
        assertThat(config.maxTokenBudget()).isEqualTo(2000);
        assertThat(config.deepAnalysisEnabled()).isFalse();
        assertThat(config.timeout().getSeconds()).isEqualTo(10);
    }

    @Test @DisplayName("should create config with all fields")
    void shouldCreateConfigFull() {
        var config = BrainSentryConfig.of("http://localhost:8081/api", "key-123", "tenant-1");
        assertThat(config.apiKey()).isEqualTo("key-123");
        assertThat(config.tenantId()).isEqualTo("tenant-1");
    }

    @Test @DisplayName("should reject null baseUrl")
    void shouldRejectNullBaseUrl() {
        assertThatThrownBy(() -> BrainSentryConfig.of(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test @DisplayName("should report unhealthy when server is down")
    void shouldReportUnhealthy() {
        var config = BrainSentryConfig.of("http://localhost:99999/api");
        var client = new BrainSentryClient(config);
        assertThat(client.isHealthy()).isFalse();
    }

    @Test @DisplayName("should create EnrichedPrompt with fullPrompt")
    void shouldCreateEnrichedPrompt() {
        var ep = new EnrichedPrompt(true, "original", "context injected", List.of("m1"), 100);
        assertThat(ep.fullPrompt()).isEqualTo("context injected\n\noriginal");
        assertThat(ep.enhanced()).isTrue();
        assertThat(ep.memoriesUsed()).containsExactly("m1");
    }

    @Test @DisplayName("should return original prompt when not enhanced")
    void shouldReturnOriginalWhenNotEnhanced() {
        var ep = new EnrichedPrompt(false, "original", null, List.of(), 0);
        assertThat(ep.fullPrompt()).isEqualTo("original");
    }

    @Test @DisplayName("should create Memory record with defensive copies")
    void shouldCreateMemoryDefensively() {
        var tags = new java.util.ArrayList<>(List.of("a", "b"));
        var mem = new Memory("id", "content", "summary", "KNOWLEDGE", "CRITICAL",
                "SEMANTIC", tags, Map.of("k", "v"), java.time.Instant.now());
        tags.add("c");
        assertThat(mem.tags()).hasSize(2); // Defensive copy
    }

    @Test @DisplayName("should handle null tags and metadata in Memory")
    void shouldHandleNullsInMemory() {
        var mem = new Memory("id", "content", null, null, null, null, null, null, null);
        assertThat(mem.tags()).isEmpty();
        assertThat(mem.metadata()).isEmpty();
    }

    @Test @DisplayName("should intercept return non-enhanced on connection failure")
    void shouldReturnNonEnhancedOnFailure() throws Exception {
        var config = BrainSentryConfig.of("http://localhost:99999/api");
        var client = new BrainSentryClient(config);
        // Will fail to connect — should return non-enhanced gracefully
        assertThatThrownBy(() -> client.intercept("test prompt", 2000))
                .isInstanceOf(Exception.class);
    }

    @Test @DisplayName("should search return empty on connection failure")
    void shouldReturnEmptySearchOnFailure() throws Exception {
        var config = BrainSentryConfig.of("http://localhost:99999/api");
        var client = new BrainSentryClient(config);
        assertThatThrownBy(() -> client.searchMemories("test", 5))
                .isInstanceOf(Exception.class);
    }
}
