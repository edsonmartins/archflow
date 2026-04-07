package br.com.archflow.brainsentry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

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
}
