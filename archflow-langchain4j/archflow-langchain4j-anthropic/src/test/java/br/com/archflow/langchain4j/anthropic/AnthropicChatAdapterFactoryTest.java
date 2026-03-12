package br.com.archflow.langchain4j.anthropic;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("AnthropicChatAdapterFactory")
class AnthropicChatAdapterFactoryTest {

    private AnthropicChatAdapterFactory factory;

    @BeforeEach
    void setUp() {
        factory = new AnthropicChatAdapterFactory();
    }

    @Test
    @DisplayName("should return 'anthropic' as provider")
    void shouldReturnProvider() {
        assertThat(factory.getProvider()).isEqualTo("anthropic");
    }

    @Test
    @DisplayName("should support 'chat' type")
    void shouldSupportChat() {
        assertThat(factory.supports("chat")).isTrue();
    }

    @Test
    @DisplayName("should support 'model' type")
    void shouldSupportModel() {
        assertThat(factory.supports("model")).isTrue();
    }

    @Test
    @DisplayName("should not support unknown types")
    void shouldNotSupportUnknown() {
        assertThat(factory.supports("streaming")).isFalse();
        assertThat(factory.supports("embedding")).isFalse();
        assertThat(factory.supports("")).isFalse();
    }
}
