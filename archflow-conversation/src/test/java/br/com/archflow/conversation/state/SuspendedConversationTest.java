package br.com.archflow.conversation.state;

import br.com.archflow.conversation.form.FormData;
import br.com.archflow.conversation.state.SuspendedConversation.ConversationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SuspendedConversation")
class SuspendedConversationTest {

    private FormData sampleForm() {
        return FormData.builder()
                .title("Test Form")
                .addField(FormData.FormField.text("name", "Name").build())
                .build();
    }

    private SuspendedConversation.Builder defaultBuilder() {
        return SuspendedConversation.builder()
                .conversationId("conv-1")
                .resumeToken("token-abc")
                .workflowId("wf-1")
                .form(sampleForm())
                .expiresAt(Instant.now().plus(30, ChronoUnit.MINUTES));
    }

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should build with all required fields")
        void shouldBuildWithRequiredFields() {
            // Arrange & Act
            SuspendedConversation conversation = defaultBuilder().build();

            // Assert
            assertThat(conversation.getConversationId()).isEqualTo("conv-1");
            assertThat(conversation.getResumeToken()).isEqualTo("token-abc");
            assertThat(conversation.getWorkflowId()).isEqualTo("wf-1");
            assertThat(conversation.getForm()).isNotNull();
            assertThat(conversation.getStatus()).isEqualTo(ConversationStatus.WAITING);
            assertThat(conversation.getCreatedAt()).isNotNull();
            assertThat(conversation.getContext()).isEmpty();
            assertThat(conversation.getPriority()).isZero();
        }

        @Test
        @DisplayName("should build with all optional fields")
        void shouldBuildWithAllOptionalFields() {
            // Arrange
            Instant now = Instant.now();
            Map<String, Object> ctx = Map.of("key", "value");

            // Act
            SuspendedConversation conversation = defaultBuilder()
                    .workflowExecutionId("exec-1")
                    .createdAt(now)
                    .status(ConversationStatus.WAITING)
                    .context(ctx)
                    .priority(5)
                    .build();

            // Assert
            assertThat(conversation.getWorkflowExecutionId()).isEqualTo("exec-1");
            assertThat(conversation.getCreatedAt()).isEqualTo(now);
            assertThat(conversation.getContext()).containsEntry("key", "value");
            assertThat(conversation.getPriority()).isEqualTo(5);
        }

        @Test
        @DisplayName("should throw when conversationId is missing")
        void shouldThrowWhenConversationIdMissing() {
            assertThatThrownBy(() ->
                    SuspendedConversation.builder()
                            .resumeToken("token")
                            .build()
            ).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("conversationId");
        }

        @Test
        @DisplayName("should throw when resumeToken is missing")
        void shouldThrowWhenResumeTokenMissing() {
            assertThatThrownBy(() ->
                    SuspendedConversation.builder()
                            .conversationId("conv-1")
                            .build()
            ).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("resumeToken");
        }

        @Test
        @DisplayName("should default status to WAITING")
        void shouldDefaultStatusToWaiting() {
            SuspendedConversation conversation = defaultBuilder().build();
            assertThat(conversation.getStatus()).isEqualTo(ConversationStatus.WAITING);
        }

        @Test
        @DisplayName("should default context to empty map when null")
        void shouldDefaultContextToEmptyMap() {
            SuspendedConversation conversation = defaultBuilder().context(null).build();
            assertThat(conversation.getContext()).isNotNull().isEmpty();
        }
    }

    @Nested
    @DisplayName("isExpired")
    class IsExpiredTests {

        @Test
        @DisplayName("should return false when not expired")
        void shouldReturnFalseWhenNotExpired() {
            // Arrange
            SuspendedConversation conversation = defaultBuilder()
                    .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                    .build();

            // Act & Assert
            assertThat(conversation.isExpired()).isFalse();
        }

        @Test
        @DisplayName("should return true when expired")
        void shouldReturnTrueWhenExpired() {
            // Arrange
            SuspendedConversation conversation = defaultBuilder()
                    .expiresAt(Instant.now().minus(1, ChronoUnit.MINUTES))
                    .build();

            // Act & Assert
            assertThat(conversation.isExpired()).isTrue();
        }

        @Test
        @DisplayName("should return false when expiresAt is null")
        void shouldReturnFalseWhenExpiresAtIsNull() {
            // Arrange
            SuspendedConversation conversation = defaultBuilder()
                    .expiresAt(null)
                    .build();

            // Act & Assert
            assertThat(conversation.isExpired()).isFalse();
        }
    }

    @Nested
    @DisplayName("isActive")
    class IsActiveTests {

        @Test
        @DisplayName("should return true when WAITING and not expired")
        void shouldReturnTrueWhenWaitingAndNotExpired() {
            SuspendedConversation conversation = defaultBuilder()
                    .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                    .build();

            assertThat(conversation.isActive()).isTrue();
        }

        @Test
        @DisplayName("should return false when expired")
        void shouldReturnFalseWhenExpired() {
            SuspendedConversation conversation = defaultBuilder()
                    .expiresAt(Instant.now().minus(1, ChronoUnit.MINUTES))
                    .build();

            assertThat(conversation.isActive()).isFalse();
        }

        @Test
        @DisplayName("should return false when RESUMED")
        void shouldReturnFalseWhenResumed() {
            SuspendedConversation conversation = defaultBuilder()
                    .status(ConversationStatus.RESUMED)
                    .build();

            assertThat(conversation.isActive()).isFalse();
        }

        @Test
        @DisplayName("should return false when CANCELLED")
        void shouldReturnFalseWhenCancelled() {
            SuspendedConversation conversation = defaultBuilder()
                    .status(ConversationStatus.CANCELLED)
                    .build();

            assertThat(conversation.isActive()).isFalse();
        }

        @Test
        @DisplayName("should return false when TIMED_OUT")
        void shouldReturnFalseWhenTimedOut() {
            SuspendedConversation conversation = defaultBuilder()
                    .status(ConversationStatus.TIMED_OUT)
                    .build();

            assertThat(conversation.isActive()).isFalse();
        }
    }

    @Nested
    @DisplayName("State Transitions")
    class StateTransitionTests {

        @Test
        @DisplayName("resume should produce RESUMED status with form data in context")
        void resumeShouldProduceResumedStatus() {
            // Arrange
            SuspendedConversation original = defaultBuilder().build();
            Map<String, Object> formData = Map.of("name", "John");

            // Act
            SuspendedConversation resumed = original.resume(formData);

            // Assert
            assertThat(resumed.getStatus()).isEqualTo(ConversationStatus.RESUMED);
            assertThat(resumed.getConversationId()).isEqualTo(original.getConversationId());
            assertThat(resumed.getResumeToken()).isEqualTo(original.getResumeToken());
            assertThat(resumed.getWorkflowId()).isEqualTo(original.getWorkflowId());
            assertThat(resumed.getCreatedAt()).isEqualTo(original.getCreatedAt());
            assertThat(resumed.getExpiresAt()).isEqualTo(original.getExpiresAt());
            assertThat(resumed.getContext()).containsKey("formData");
            assertThat(resumed.getContext()).containsKey("originalContext");
        }

        @Test
        @DisplayName("cancel should produce CANCELLED status preserving original context")
        void cancelShouldProduceCancelledStatus() {
            // Arrange
            Map<String, Object> ctx = Map.of("key", "value");
            SuspendedConversation original = defaultBuilder().context(ctx).build();

            // Act
            SuspendedConversation cancelled = original.cancel();

            // Assert
            assertThat(cancelled.getStatus()).isEqualTo(ConversationStatus.CANCELLED);
            assertThat(cancelled.getConversationId()).isEqualTo(original.getConversationId());
            assertThat(cancelled.getContext()).containsEntry("key", "value");
        }

        @Test
        @DisplayName("timeout should produce TIMED_OUT status preserving original context")
        void timeoutShouldProduceTimedOutStatus() {
            // Arrange
            Map<String, Object> ctx = Map.of("key", "value");
            SuspendedConversation original = defaultBuilder().context(ctx).build();

            // Act
            SuspendedConversation timedOut = original.timeout();

            // Assert
            assertThat(timedOut.getStatus()).isEqualTo(ConversationStatus.TIMED_OUT);
            assertThat(timedOut.getConversationId()).isEqualTo(original.getConversationId());
            assertThat(timedOut.getContext()).containsEntry("key", "value");
        }
    }

    @Nested
    @DisplayName("Immutability")
    class ImmutabilityTests {

        @Test
        @DisplayName("context should be unmodifiable")
        void contextShouldBeUnmodifiable() {
            SuspendedConversation conversation = defaultBuilder()
                    .context(Map.of("key", "value"))
                    .build();

            assertThatThrownBy(() -> conversation.getContext().put("new", "entry"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("resume should not mutate original")
        void resumeShouldNotMutateOriginal() {
            SuspendedConversation original = defaultBuilder().build();
            original.resume(Map.of("name", "John"));

            assertThat(original.getStatus()).isEqualTo(ConversationStatus.WAITING);
        }

        @Test
        @DisplayName("cancel should not mutate original")
        void cancelShouldNotMutateOriginal() {
            SuspendedConversation original = defaultBuilder().build();
            original.cancel();

            assertThat(original.getStatus()).isEqualTo(ConversationStatus.WAITING);
        }

        @Test
        @DisplayName("timeout should not mutate original")
        void timeoutShouldNotMutateOriginal() {
            SuspendedConversation original = defaultBuilder().build();
            original.timeout();

            assertThat(original.getStatus()).isEqualTo(ConversationStatus.WAITING);
        }
    }
}
