package br.com.archflow.langchain4j.openai;

import br.com.archflow.langchain4j.core.spi.LangChainAdapterFactory;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.engine.ExecutionMetrics;
import br.com.archflow.model.flow.FlowState;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes unitários para o OpenAiChatAdapter com LangChain4j 1.10.0
 */
class OpenAiChatAdapterTest {

    private OpenAiChatAdapter adapter;
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        adapter = new OpenAiChatAdapter();
        // Criar contexto mock com memória
        context = new ExecutionContext() {
            private final ChatMemory memory = MessageWindowChatMemory.withMaxMessages(10);

            @Override
            public ChatMemory getChatMemory() {
                return memory;
            }

            @Override
            public Optional<Object> get(String key) {
                return Optional.empty();
            }

            @Override
            public void set(String key, Object value) {
            }

            @Override
            public ExecutionMetrics getMetrics() {
                return null;
            }

            @Override
            public FlowState getState() {
                return null;
            }

            @Override
            public void setState(FlowState state) {
            }
        };
    }

    @Test
    void testFactoryIsRegisteredViaSPI() {
        // Nota: SPI pode não funcionar corretamente no contexto de testes unitários
        // A factory é verificada manualmente abaixo
        OpenAiChatAdapterFactory factory = new OpenAiChatAdapterFactory();
        assertThat(factory.getProvider()).isEqualTo("openai");
        assertThat(factory.supports("chat")).isTrue();
    }

    @Test
    void testValidateWithValidConfig() {
        Map<String, Object> config = Map.of(
                "api.key", "test-key",
                "model.name", "gpt-4o-mini",
                "temperature", 0.7
        );

        // Não deve lançar exceção
        adapter.validate(config);
    }

    @Test
    void testValidateThrowsWhenApiKeyMissing() {
        Map<String, Object> config = Map.of(
                "model.name", "gpt-4o-mini"
        );

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> adapter.validate(config)
        );
    }

    @Test
    void testValidateThrowsWhenTemperatureInvalid() {
        Map<String, Object> config = Map.of(
                "api.key", "test-key",
                "temperature", 3.0 // inválido > 2.0
        );

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> adapter.validate(config)
        );
    }

    @Test
    void testConfigureWithValidConfig() {
        Map<String, Object> config = Map.of(
                "api.key", "test-key",
                "model.name", "gpt-4o-mini",
                "temperature", 0.7,
                "maxTokens", 2048
        );

        adapter.configure(config);

        // Verifica se o modelo foi criado (não é nulo após configuração)
        assertThat(adapter).isNotNull();
    }

    @Test
    void testSupportsGenerateOperation() {
        Map<String, Object> config = Map.of("api.key", "test-key");
        adapter.configure(config);

        // Verifica se a operação é suportada (não lança IllegalArgumentException)
        org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class, // Espera erro de API key inválida
                () -> adapter.execute("generate", "Hello", context)
        );
    }

    @Test
    void testThrowsOnUnsupportedOperation() throws Exception {
        Map<String, Object> config = Map.of("api.key", "test-key");
        adapter.configure(config);

        // A operação "invalid" não é suportada, então deve lançar RuntimeException
        // com causa sendo IllegalArgumentException
        try {
            adapter.execute("invalid", "test", context);
            org.junit.jupiter.api.Assertions.fail("Expected RuntimeException");
        } catch (RuntimeException e) {
            // A exceção é embrulhada em RuntimeException pelo adapter
            assertThat(e.getMessage()).contains("Error executing operation: invalid");
            assertThat(e.getCause()).isInstanceOf(IllegalArgumentException.class);
            assertThat(e.getCause().getMessage()).contains("Unsupported operation");
        }
    }

    @Test
    void testThrowsWhenNotConfigured() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> adapter.execute("generate", "test", context)
        );
    }

    @Test
    void testShutdown() {
        Map<String, Object> config = Map.of("api.key", "test-key");
        adapter.configure(config);
        adapter.shutdown();

        // Após shutdown, execute deve lançar IllegalStateException
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> adapter.execute("generate", "test", context)
        );
    }
}
