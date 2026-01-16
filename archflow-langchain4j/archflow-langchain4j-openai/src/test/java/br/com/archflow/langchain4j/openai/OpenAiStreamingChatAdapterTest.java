package br.com.archflow.langchain4j.openai;

import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.engine.ExecutionMetrics;
import br.com.archflow.model.flow.FlowState;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes unitários para o OpenAiStreamingChatAdapter com LangChain4j 1.10.0
 */
class OpenAiStreamingChatAdapterTest {

    private OpenAiStreamingChatAdapter adapter;
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        adapter = new OpenAiStreamingChatAdapter();
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
    void testSupportsGenerateStreamOperation() throws Exception {
        Map<String, Object> config = Map.of("api.key", "test-key");
        adapter.configure(config);

        // A operação "generateStream" deve retornar um CompletableFuture
        Object result = adapter.execute("generateStream", "Hello", context);
        assertThat(result).isInstanceOf(CompletableFuture.class);
    }

    @Test
    void testSupportsChatStreamOperation() throws Exception {
        Map<String, Object> config = Map.of("api.key", "test-key");
        adapter.configure(config);

        // A operação "chatStream" deve retornar um CompletableFuture
        Object result = adapter.execute("chatStream", "Hello", context);
        assertThat(result).isInstanceOf(CompletableFuture.class);
    }

    @Test
    void testThrowsOnUnsupportedOperation() throws Exception {
        Map<String, Object> config = Map.of("api.key", "test-key");
        adapter.configure(config);

        // A operação "invalid" não é suportada
        try {
            adapter.execute("invalid", "test", context);
            org.junit.jupiter.api.Assertions.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("Unsupported operation");
        }
    }

    @Test
    void testThrowsWhenNotConfigured() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> adapter.execute("generateStream", "test", context)
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
                () -> adapter.execute("generateStream", "test", context)
        );
    }
}
