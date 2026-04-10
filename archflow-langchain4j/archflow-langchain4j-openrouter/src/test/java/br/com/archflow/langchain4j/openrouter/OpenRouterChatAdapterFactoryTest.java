package br.com.archflow.langchain4j.openrouter;

import br.com.archflow.langchain4j.core.spi.LangChainAdapterFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.*;

@DisplayName("OpenRouterChatAdapterFactory")
class OpenRouterChatAdapterFactoryTest {

    @Test
    @DisplayName("should return 'openrouter' as provider name")
    void shouldReturnProviderName() {
        var factory = new OpenRouterChatAdapterFactory();
        assertThat(factory.getProvider()).isEqualTo("openrouter");
    }

    @Test
    @DisplayName("should support 'chat' type")
    void shouldSupportChat() {
        var factory = new OpenRouterChatAdapterFactory();
        assertThat(factory.supports("chat")).isTrue();
        assertThat(factory.supports("Chat")).isTrue();
        assertThat(factory.supports("embedding")).isFalse();
    }

    @Test
    @DisplayName("should be discoverable via ServiceLoader SPI")
    void shouldBeDiscoverableViaSpi() {
        ServiceLoader<LangChainAdapterFactory> loader = ServiceLoader.load(LangChainAdapterFactory.class);
        boolean found = false;
        for (LangChainAdapterFactory factory : loader) {
            if ("openrouter".equals(factory.getProvider())) {
                found = true;
                break;
            }
        }
        assertThat(found).isTrue();
    }
}
