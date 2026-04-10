package br.com.archflow.langchain4j.openrouter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("OpenRouterChatAdapter")
class OpenRouterChatAdapterTest {

    @Test
    @DisplayName("should reject null properties")
    void shouldRejectNullProperties() {
        var adapter = new OpenRouterChatAdapter();
        assertThatThrownBy(() -> adapter.validate(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should reject missing API key")
    void shouldRejectMissingApiKey() {
        var adapter = new OpenRouterChatAdapter();
        assertThatThrownBy(() -> adapter.validate(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("API key");
    }

    @Test
    @DisplayName("should configure with valid properties")
    void shouldConfigureWithValidProperties() {
        var adapter = new OpenRouterChatAdapter();
        adapter.configure(Map.of(
                "api.key", "test-key",
                "model.name", "anthropic/claude-3.5-sonnet",
                "temperature", 0.5
        ));
        // Should not throw — adapter is configured
        assertThat(adapter.hasFallback()).isFalse();
    }

    @Test
    @DisplayName("should configure with fallback")
    void shouldConfigureWithFallback() {
        var adapter = new OpenRouterChatAdapter();
        adapter.configure(Map.of(
                "api.key", "test-key",
                "model.name", "openai/gpt-4o",
                "fallback.base.url", "http://localhost:11434/v1",
                "fallback.model.name", "llama3.1"
        ));
        assertThat(adapter.hasFallback()).isTrue();
    }

    @Test
    @DisplayName("should throw when executing without configure")
    void shouldThrowWithoutConfigure() {
        var adapter = new OpenRouterChatAdapter();
        assertThatThrownBy(() -> adapter.execute("generate", "hello", null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("should shutdown cleanly")
    void shouldShutdownCleanly() {
        var adapter = new OpenRouterChatAdapter();
        adapter.configure(Map.of("api.key", "test-key"));
        adapter.shutdown();
        assertThat(adapter.hasFallback()).isFalse();
    }
}
