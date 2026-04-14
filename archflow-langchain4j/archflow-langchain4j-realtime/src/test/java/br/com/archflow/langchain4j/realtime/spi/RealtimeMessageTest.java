package br.com.archflow.langchain4j.realtime.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link RealtimeMessage}.
 */
@DisplayName("RealtimeMessage")
class RealtimeMessageTest {

    // ========== Constructor ==========

    @Nested
    @DisplayName("constructor validation")
    class ConstructorValidation {

        @Test
        @DisplayName("accepts valid type and non-null data")
        void constructorWithValidTypeAndData() {
            Map<String, Object> data = Map.of("key", "value");
            RealtimeMessage msg = new RealtimeMessage("ready", data);

            assertThat(msg.type()).isEqualTo("ready");
            assertThat(msg.data()).containsEntry("key", "value");
        }

        @Test
        @DisplayName("throws IllegalArgumentException when type is null")
        void constructorWithNullTypeThrows() {
            assertThatThrownBy(() -> new RealtimeMessage(null, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("type is required");
        }

        @Test
        @DisplayName("throws IllegalArgumentException when type is blank")
        void constructorWithBlankTypeThrows() {
            assertThatThrownBy(() -> new RealtimeMessage("   ", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("type is required");
        }

        @Test
        @DisplayName("throws IllegalArgumentException when type is empty string")
        void constructorWithEmptyTypeThrows() {
            assertThatThrownBy(() -> new RealtimeMessage("", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("type is required");
        }

        @Test
        @DisplayName("defaults null data to empty map")
        void constructorWithNullDataDefaultsToEmptyMap() {
            RealtimeMessage msg = new RealtimeMessage("ping", null);

            assertThat(msg.data()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("data map returned is unmodifiable")
        void dataMapIsUnmodifiable() {
            RealtimeMessage msg = new RealtimeMessage("ready", Map.of("k", "v"));

            assertThatThrownBy(() -> msg.data().put("extra", "val"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("data map from null is unmodifiable")
        void dataMapFromNullIsUnmodifiable() {
            RealtimeMessage msg = new RealtimeMessage("ping", null);

            assertThatThrownBy(() -> msg.data().put("extra", "val"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // ========== Static factories ==========

    @Nested
    @DisplayName("ready()")
    class ReadyFactory {

        @Test
        @DisplayName("creates message with type 'ready' and sessionId in data")
        void readyCreatesCorrectMessage() {
            RealtimeMessage msg = RealtimeMessage.ready("session-abc");

            assertThat(msg.type()).isEqualTo("ready");
            assertThat(msg.data()).containsEntry("sessionId", "session-abc");
            assertThat(msg.data()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("transcript()")
    class TranscriptFactory {

        @Test
        @DisplayName("creates message with type 'transcript' and all fields populated")
        void transcriptCreatesCorrectMessage() {
            RealtimeMessage msg = RealtimeMessage.transcript("user", "Hello world", true);

            assertThat(msg.type()).isEqualTo("transcript");
            assertThat(msg.data())
                    .containsEntry("speaker", "user")
                    .containsEntry("text", "Hello world")
                    .containsEntry("final", true);
            assertThat(msg.data()).hasSize(3);
        }

        @Test
        @DisplayName("isFinal=false is preserved in data")
        void transcriptPreservesIsFinalFalse() {
            RealtimeMessage msg = RealtimeMessage.transcript("agent", "Partial...", false);

            assertThat(msg.data()).containsEntry("final", false);
        }
    }

    @Nested
    @DisplayName("audio()")
    class AudioFactory {

        @Test
        @DisplayName("creates message with type 'audio' and pcm16/sampleRate in data")
        void audioCreatesCorrectMessage() {
            RealtimeMessage msg = RealtimeMessage.audio("base64encodedPCM==", 16000);

            assertThat(msg.type()).isEqualTo("audio");
            assertThat(msg.data())
                    .containsEntry("pcm16", "base64encodedPCM==")
                    .containsEntry("sampleRate", 16000);
            assertThat(msg.data()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("agentDone()")
    class AgentDoneFactory {

        @Test
        @DisplayName("creates message with type 'agent_done' and empty data")
        void agentDoneCreatesCorrectMessage() {
            RealtimeMessage msg = RealtimeMessage.agentDone();

            assertThat(msg.type()).isEqualTo("agent_done");
            assertThat(msg.data()).isEmpty();
        }
    }

    @Nested
    @DisplayName("error()")
    class ErrorFactory {

        @Test
        @DisplayName("creates message with type 'error' and message in data")
        void errorCreatesCorrectMessage() {
            RealtimeMessage msg = RealtimeMessage.error("Something went wrong");

            assertThat(msg.type()).isEqualTo("error");
            assertThat(msg.data()).containsEntry("message", "Something went wrong");
            assertThat(msg.data()).hasSize(1);
        }
    }

    // ========== Record semantics ==========

    @Nested
    @DisplayName("record equality and toString")
    class RecordSemantics {

        @Test
        @DisplayName("two messages with same type and data are equal")
        void equalityByTypeAndData() {
            RealtimeMessage a = new RealtimeMessage("ready", Map.of("sessionId", "s1"));
            RealtimeMessage b = new RealtimeMessage("ready", Map.of("sessionId", "s1"));

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("messages with different types are not equal")
        void inequalityByType() {
            RealtimeMessage a = new RealtimeMessage("ready", Map.of());
            RealtimeMessage b = new RealtimeMessage("error", Map.of());

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("toString includes type")
        void toStringIncludesType() {
            RealtimeMessage msg = RealtimeMessage.ready("s1");

            assertThat(msg.toString()).contains("ready");
        }
    }
}
