package br.com.archflow.conversation.event;

import br.com.archflow.conversation.event.ArchflowEvent.EventDomain;
import br.com.archflow.conversation.event.ArchflowEvent.EventType;
import br.com.archflow.conversation.form.FormData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ArchflowEvent")
class ArchflowEventTest {

    private FormData sampleForm() {
        return FormData.builder()
                .title("Test Form")
                .addField(FormData.FormField.text("name", "Name").build())
                .build();
    }

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should build event with required fields")
        void shouldBuildWithRequiredFields() {
            // Arrange & Act
            ArchflowEvent event = ArchflowEvent.builder()
                    .domain(EventDomain.CHAT)
                    .type(EventType.MESSAGE)
                    .build();

            // Assert
            assertThat(event.getEnvelope().domain()).isEqualTo(EventDomain.CHAT);
            assertThat(event.getEnvelope().type()).isEqualTo(EventType.MESSAGE);
            assertThat(event.getEnvelope().id()).isNotNull().isNotEmpty();
            assertThat(event.getEnvelope().timestamp()).isNotNull();
        }

        @Test
        @DisplayName("should build event with all optional fields")
        void shouldBuildWithAllOptionalFields() {
            // Arrange
            Instant now = Instant.now();

            // Act
            ArchflowEvent event = ArchflowEvent.builder()
                    .domain(EventDomain.CHAT)
                    .type(EventType.MESSAGE)
                    .id("custom-id")
                    .timestamp(now)
                    .correlationId("corr-1")
                    .executionId("exec-1")
                    .payload(Map.of("content", "hello"))
                    .build();

            // Assert
            assertThat(event.getEnvelope().id()).isEqualTo("custom-id");
            assertThat(event.getEnvelope().timestamp()).isEqualTo(now);
            assertThat(event.getEnvelope().correlationId()).isEqualTo("corr-1");
            assertThat(event.getEnvelope().executionId()).isEqualTo("exec-1");
            assertThat(event.getData().content()).isEqualTo("hello");
        }

        @Test
        @DisplayName("should throw when domain is missing")
        void shouldThrowWhenDomainMissing() {
            assertThatThrownBy(() ->
                    ArchflowEvent.builder()
                            .type(EventType.MESSAGE)
                            .build()
            ).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("domain and type are required");
        }

        @Test
        @DisplayName("should throw when type is missing")
        void shouldThrowWhenTypeMissing() {
            assertThatThrownBy(() ->
                    ArchflowEvent.builder()
                            .domain(EventDomain.CHAT)
                            .build()
            ).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("domain and type are required");
        }
    }

    @Nested
    @DisplayName("Factory Methods")
    class FactoryMethodTests {

        @Test
        @DisplayName("chatMessage should create CHAT/MESSAGE event with content")
        void chatMessageShouldCreateCorrectEvent() {
            // Act
            ArchflowEvent event = ArchflowEvent.chatMessage("Hello world");

            // Assert
            assertThat(event.getEnvelope().domain()).isEqualTo(EventDomain.CHAT);
            assertThat(event.getEnvelope().type()).isEqualTo(EventType.MESSAGE);
            assertThat(event.getData().content()).isEqualTo("Hello world");
        }

        @Test
        @DisplayName("chatDelta should create CHAT/DELTA event with content")
        void chatDeltaShouldCreateCorrectEvent() {
            // Act
            ArchflowEvent event = ArchflowEvent.chatDelta("chunk");

            // Assert
            assertThat(event.getEnvelope().domain()).isEqualTo(EventDomain.CHAT);
            assertThat(event.getEnvelope().type()).isEqualTo(EventType.DELTA);
            assertThat(event.getData().content()).isEqualTo("chunk");
        }

        @Test
        @DisplayName("formPresent should create INTERACTION/FORM event with formId and form")
        void formPresentShouldCreateCorrectEvent() {
            // Arrange
            FormData form = sampleForm();

            // Act
            ArchflowEvent event = ArchflowEvent.formPresent("form-1", form);

            // Assert
            assertThat(event.getEnvelope().domain()).isEqualTo(EventDomain.INTERACTION);
            assertThat(event.getEnvelope().type()).isEqualTo(EventType.FORM);
            assertThat(event.getData().formId()).isEqualTo("form-1");
            assertThat(event.getData().form()).isEqualTo(form);
        }

        @Test
        @DisplayName("suspend should create INTERACTION/SUSPEND event")
        void suspendShouldCreateCorrectEvent() {
            // Arrange
            FormData form = sampleForm();

            // Act
            ArchflowEvent event = ArchflowEvent.suspend("conv-1", "token-abc", form);

            // Assert
            assertThat(event.getEnvelope().domain()).isEqualTo(EventDomain.INTERACTION);
            assertThat(event.getEnvelope().type()).isEqualTo(EventType.SUSPEND);
            assertThat(event.getData().payload())
                    .containsEntry("conversationId", "conv-1")
                    .containsEntry("resumeToken", "token-abc");
            assertThat(event.getData().form()).isEqualTo(form);
        }

        @Test
        @DisplayName("resume should create INTERACTION/RESUME event with form data")
        void resumeShouldCreateCorrectEvent() {
            // Arrange
            Map<String, Object> formData = Map.of("name", "John");

            // Act
            ArchflowEvent event = ArchflowEvent.resume("conv-1", formData);

            // Assert
            assertThat(event.getEnvelope().domain()).isEqualTo(EventDomain.INTERACTION);
            assertThat(event.getEnvelope().type()).isEqualTo(EventType.RESUME);
            assertThat(event.getData().payload())
                    .containsEntry("conversationId", "conv-1")
                    .containsEntry("formData", formData);
        }

        @Test
        @DisplayName("toolStart should create TOOL/START event with toolName and input")
        void toolStartShouldCreateCorrectEvent() {
            // Arrange
            Map<String, Object> input = Map.of("query", "test");

            // Act
            ArchflowEvent event = ArchflowEvent.toolStart("search", input);

            // Assert
            assertThat(event.getEnvelope().domain()).isEqualTo(EventDomain.TOOL);
            assertThat(event.getEnvelope().type()).isEqualTo(EventType.START);
            assertThat(event.getData().toolName()).isEqualTo("search");
            assertThat(event.getData().input()).containsEntry("query", "test");
            assertThat(event.getData().status()).isEqualTo("started");
        }

        @Test
        @DisplayName("toolComplete should create TOOL/COMPLETE event with result")
        void toolCompleteShouldCreateCorrectEvent() {
            // Act
            ArchflowEvent event = ArchflowEvent.toolComplete("search", "result-data");

            // Assert
            assertThat(event.getEnvelope().domain()).isEqualTo(EventDomain.TOOL);
            assertThat(event.getEnvelope().type()).isEqualTo(EventType.COMPLETE);
            assertThat(event.getData().toolName()).isEqualTo("search");
            assertThat(event.getData().result()).isEqualTo("result-data");
            assertThat(event.getData().status()).isEqualTo("completed");
        }

        @Test
        @DisplayName("error should create AUDIT/ERROR event with message and cause")
        void errorShouldCreateCorrectEvent() {
            // Arrange
            RuntimeException cause = new RuntimeException("something failed");

            // Act
            ArchflowEvent event = ArchflowEvent.error("An error occurred", cause);

            // Assert
            assertThat(event.getEnvelope().domain()).isEqualTo(EventDomain.AUDIT);
            assertThat(event.getEnvelope().type()).isEqualTo(EventType.ERROR);
            assertThat(event.getData().message()).isEqualTo("An error occurred");
            assertThat(event.getData().cause()).isEqualTo("something failed");
        }

        @Test
        @DisplayName("error should handle null cause")
        void errorShouldHandleNullCause() {
            // Act
            ArchflowEvent event = ArchflowEvent.error("An error occurred", null);

            // Assert
            assertThat(event.getData().message()).isEqualTo("An error occurred");
            assertThat(event.getData().cause()).isNull();
        }

        @Test
        @DisplayName("thinking should create THINKING/DELTA event with thought")
        void thinkingShouldCreateCorrectEvent() {
            // Act
            ArchflowEvent event = ArchflowEvent.thinking("Let me analyze this...");

            // Assert
            assertThat(event.getEnvelope().domain()).isEqualTo(EventDomain.THINKING);
            assertThat(event.getEnvelope().type()).isEqualTo(EventType.DELTA);
            assertThat(event.getData().thought()).isEqualTo("Let me analyze this...");
        }
    }

    @Nested
    @DisplayName("Envelope Fields")
    class EnvelopeFieldTests {

        @Test
        @DisplayName("should auto-generate UUID id when not provided")
        void shouldAutoGenerateId() {
            ArchflowEvent event1 = ArchflowEvent.chatMessage("test1");
            ArchflowEvent event2 = ArchflowEvent.chatMessage("test2");

            assertThat(event1.getEnvelope().id()).isNotEqualTo(event2.getEnvelope().id());
        }

        @Test
        @DisplayName("should auto-generate timestamp when not provided")
        void shouldAutoGenerateTimestamp() {
            Instant before = Instant.now();
            ArchflowEvent event = ArchflowEvent.chatMessage("test");
            Instant after = Instant.now();

            assertThat(event.getEnvelope().timestamp())
                    .isAfterOrEqualTo(before)
                    .isBeforeOrEqualTo(after);
        }

        @Test
        @DisplayName("correlationId and executionId should be null by default")
        void correlationAndExecutionShouldBeNullByDefault() {
            ArchflowEvent event = ArchflowEvent.chatMessage("test");

            assertThat(event.getEnvelope().correlationId()).isNull();
            assertThat(event.getEnvelope().executionId()).isNull();
        }
    }

    @Nested
    @DisplayName("toJson")
    class ToJsonTests {

        @Test
        @DisplayName("should produce valid JSON-like string")
        void shouldProduceJsonString() {
            ArchflowEvent event = ArchflowEvent.chatMessage("hello");
            String json = event.toJson();

            assertThat(json).contains("\"domain\":\"chat\"");
            assertThat(json).contains("\"type\":\"message\"");
            assertThat(json).contains("envelope");
            assertThat(json).contains("data");
        }
    }
}
