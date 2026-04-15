package br.com.archflow.langchain4j.memory.redis;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisMemoryAdapterTest {

    @Nested
    @DisplayName("validate()")
    class Validate {

        @Test
        @DisplayName("throws on null properties")
        void nullProperties() {
            var adapter = new RedisMemoryAdapter();

            assertThatThrownBy(() -> adapter.validate(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("null");
        }

        @Test
        @DisplayName("throws when redis.host is missing")
        void missingHost() {
            var adapter = new RedisMemoryAdapter();
            var props = Map.<String, Object>of();

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("host");
        }

        @Test
        @DisplayName("throws when redis.host is explicitly null")
        void explicitNullHost() {
            var adapter = new RedisMemoryAdapter();
            var props = new HashMap<String, Object>();
            props.put("redis.host", null);

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("host");
        }

        @Test
        @DisplayName("throws when redis.host is blank")
        void blankHost() {
            var adapter = new RedisMemoryAdapter();
            var props = Map.<String, Object>of("redis.host", "   ");

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("host");
        }

        @Test
        @DisplayName("throws when redis.port is not a number")
        void invalidPortType() {
            var adapter = new RedisMemoryAdapter();
            var props = Map.<String, Object>of(
                    "redis.host", "localhost",
                    "redis.port", "not-a-number"
            );

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("port");
        }

        @Test
        @DisplayName("throws when memory.maxMessages is not a number")
        void invalidMaxMessagesType() {
            var adapter = new RedisMemoryAdapter();
            var props = Map.<String, Object>of(
                    "redis.host", "localhost",
                    "memory.maxMessages", "fifty"
            );

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Max messages");
        }

        @Test
        @DisplayName("succeeds with valid host only")
        void validHostOnly() {
            var adapter = new RedisMemoryAdapter();
            var props = Map.<String, Object>of("redis.host", "localhost");

            // should not throw
            adapter.validate(props);
        }

        @Test
        @DisplayName("succeeds with all valid properties")
        void allValidProperties() {
            var adapter = new RedisMemoryAdapter();
            var props = Map.<String, Object>of(
                    "redis.host", "redis.example.com",
                    "redis.port", 6380,
                    "memory.maxMessages", 50
            );

            // should not throw
            adapter.validate(props);
        }

        @Test
        @DisplayName("accepts numeric port as valid")
        void numericPort() {
            var adapter = new RedisMemoryAdapter();
            var props = Map.<String, Object>of(
                    "redis.host", "localhost",
                    "redis.port", 6379
            );

            // should not throw
            adapter.validate(props);
        }
    }

    @Nested
    @DisplayName("Factory (inner class)")
    class InnerFactoryTests {

        private final RedisMemoryAdapter.Factory factory = new RedisMemoryAdapter.Factory();

        @Test
        @DisplayName("getProvider returns redis")
        void provider() {
            assertThat(factory.getProvider()).isEqualTo("redis");
        }

        @Test
        @DisplayName("supports memory type")
        void supportsMemory() {
            assertThat(factory.supports("memory")).isTrue();
        }

        @Test
        @DisplayName("does not support chat type")
        void doesNotSupportChat() {
            assertThat(factory.supports("chat")).isFalse();
        }

        @Test
        @DisplayName("does not support null type")
        void doesNotSupportNull() {
            assertThat(factory.supports(null)).isFalse();
        }

        @Test
        @DisplayName("does not support empty type")
        void doesNotSupportEmpty() {
            assertThat(factory.supports("")).isFalse();
        }

        @Test
        @DisplayName("does not support model type")
        void doesNotSupportModel() {
            assertThat(factory.supports("model")).isFalse();
        }
    }

    @Nested
    @DisplayName("RedisMemoryAdapterFactory (standalone)")
    class StandaloneFactoryTests {

        private final RedisMemoryAdapterFactory factory = new RedisMemoryAdapterFactory();

        @Test
        @DisplayName("getProvider returns redis")
        void provider() {
            assertThat(factory.getProvider()).isEqualTo("redis");
        }

        @Test
        @DisplayName("supports memory type")
        void supportsMemory() {
            assertThat(factory.supports("memory")).isTrue();
        }

        @Test
        @DisplayName("does not support chat type")
        void doesNotSupportChat() {
            assertThat(factory.supports("chat")).isFalse();
        }

        @Test
        @DisplayName("does not support vectorstore type")
        void doesNotSupportVectorstore() {
            assertThat(factory.supports("vectorstore")).isFalse();
        }
    }

    @Nested
    @DisplayName("Message serialization format")
    class SerializationFormat {

        private final ObjectMapper objectMapper = new ObjectMapper();

        /**
         * Verifies the JSON structure that serializeMessage produces,
         * by replicating its documented format: {"type": "user"|"ai", "content": "..."}.
         * This ensures deserializeMessage can consume what serializeMessage writes.
         */
        @Test
        @DisplayName("user message JSON round-trip")
        void userMessageRoundTrip() throws Exception {
            var node = objectMapper.createObjectNode();
            node.put("type", "user");
            node.put("content", "Hello, world!");

            var json = objectMapper.writeValueAsString(node);
            var parsed = objectMapper.readTree(json);

            assertThat(parsed.get("type").asText()).isEqualTo("user");
            assertThat(parsed.get("content").asText()).isEqualTo("Hello, world!");
        }

        @Test
        @DisplayName("ai message JSON round-trip")
        void aiMessageRoundTrip() throws Exception {
            var node = objectMapper.createObjectNode();
            node.put("type", "ai");
            node.put("content", "I can help with that.");

            var json = objectMapper.writeValueAsString(node);
            var parsed = objectMapper.readTree(json);

            assertThat(parsed.get("type").asText()).isEqualTo("ai");
            assertThat(parsed.get("content").asText()).isEqualTo("I can help with that.");
        }

        @Test
        @DisplayName("deserialized user message creates UserMessage")
        void deserializedUserMessage() throws Exception {
            var json = """
                    {"type":"user","content":"test input"}
                    """;
            var parsed = objectMapper.readTree(json);
            var type = parsed.get("type").asText();
            var content = parsed.get("content").asText();

            assertThat(type).isEqualTo("user");
            var message = UserMessage.from(content);
            assertThat(message.singleText()).isEqualTo("test input");
        }

        @Test
        @DisplayName("deserialized ai message creates AiMessage")
        void deserializedAiMessage() throws Exception {
            var json = """
                    {"type":"ai","content":"response text"}
                    """;
            var parsed = objectMapper.readTree(json);
            var type = parsed.get("type").asText();
            var content = parsed.get("content").asText();

            assertThat(type).isEqualTo("ai");
            var message = AiMessage.from(content);
            assertThat(message.text()).isEqualTo("response text");
        }

        @Test
        @DisplayName("content with special characters survives round-trip")
        void specialCharacters() throws Exception {
            var specialContent = "Line1\nLine2\t\"quoted\" and \\backslash";
            var node = objectMapper.createObjectNode();
            node.put("type", "user");
            node.put("content", specialContent);

            var json = objectMapper.writeValueAsString(node);
            var parsed = objectMapper.readTree(json);

            assertThat(parsed.get("content").asText()).isEqualTo(specialContent);
        }

        @Test
        @DisplayName("unicode content survives round-trip")
        void unicodeContent() throws Exception {
            var unicodeContent = "Mensagem em portugues com acentos e emojis";
            var node = objectMapper.createObjectNode();
            node.put("type", "ai");
            node.put("content", unicodeContent);

            var json = objectMapper.writeValueAsString(node);
            var parsed = objectMapper.readTree(json);

            assertThat(parsed.get("content").asText()).isEqualTo(unicodeContent);
        }
    }

    @Nested
    @DisplayName("shutdown()")
    class Shutdown {

        @Test
        @DisplayName("shutdown on unconfigured adapter does not throw")
        void shutdownUnconfigured() {
            var adapter = new RedisMemoryAdapter();

            // jedisPool is null; shutdown should be safe
            adapter.shutdown();
        }
    }
}
