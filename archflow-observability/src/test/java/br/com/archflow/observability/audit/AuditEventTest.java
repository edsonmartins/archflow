package br.com.archflow.observability.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AuditEvent")
class AuditEventTest {

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should build event with all fields")
        void shouldBuildEventWithAllFields() {
            // Arrange
            Instant now = Instant.now();
            Map<String, String> context = Map.of("key1", "value1", "key2", "value2");

            // Act
            AuditEvent event = AuditEvent.builder()
                    .id("test-id")
                    .timestamp(now)
                    .action(AuditAction.WORKFLOW_EXECUTE)
                    .userId("user-123")
                    .username("john.doe")
                    .resourceType("workflow")
                    .resourceId("wf-456")
                    .success(true)
                    .context(new HashMap<>(context))
                    .ipAddress("192.168.1.1")
                    .userAgent("Mozilla/5.0")
                    .sessionId("session-789")
                    .traceId("trace-abc")
                    .build();

            // Assert
            assertThat(event.getId()).isEqualTo("test-id");
            assertThat(event.getTimestamp()).isEqualTo(now);
            assertThat(event.getAction()).isEqualTo(AuditAction.WORKFLOW_EXECUTE);
            assertThat(event.getUserId()).isEqualTo("user-123");
            assertThat(event.getUsername()).isEqualTo("john.doe");
            assertThat(event.getResourceType()).isEqualTo("workflow");
            assertThat(event.getResourceId()).isEqualTo("wf-456");
            assertThat(event.isSuccess()).isTrue();
            assertThat(event.getContext()).containsEntry("key1", "value1");
            assertThat(event.getContext()).containsEntry("key2", "value2");
            assertThat(event.getIpAddress()).isEqualTo("192.168.1.1");
            assertThat(event.getUserAgent()).isEqualTo("Mozilla/5.0");
            assertThat(event.getSessionId()).isEqualTo("session-789");
            assertThat(event.getTraceId()).isEqualTo("trace-abc");
        }

        @Test
        @DisplayName("should build event with minimal fields")
        void shouldBuildEventWithMinimalFields() {
            // Act
            AuditEvent event = AuditEvent.builder()
                    .action(AuditAction.CREATE)
                    .build();

            // Assert
            assertThat(event.getId()).isNotNull().isNotEmpty();
            assertThat(event.getTimestamp()).isNotNull();
            assertThat(event.getAction()).isEqualTo(AuditAction.CREATE);
            assertThat(event.isSuccess()).isTrue();
            assertThat(event.getContext()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("should generate UUID for id when not provided")
        void shouldGenerateUuidForId() {
            // Act
            AuditEvent event1 = AuditEvent.builder().action(AuditAction.READ).build();
            AuditEvent event2 = AuditEvent.builder().action(AuditAction.READ).build();

            // Assert
            assertThat(event1.getId()).isNotNull().isNotEmpty();
            assertThat(event2.getId()).isNotNull().isNotEmpty();
            assertThat(event1.getId()).isNotEqualTo(event2.getId());
        }

        @Test
        @DisplayName("should set timestamp to now when not provided")
        void shouldSetTimestampToNowWhenNotProvided() {
            // Arrange
            Instant before = Instant.now();

            // Act
            AuditEvent event = AuditEvent.builder()
                    .action(AuditAction.CREATE)
                    .build();

            // Assert
            Instant after = Instant.now();
            assertThat(event.getTimestamp())
                    .isAfterOrEqualTo(before)
                    .isBeforeOrEqualTo(after);
        }

        @Test
        @DisplayName("should default success to true")
        void shouldDefaultSuccessToTrue() {
            // Act
            AuditEvent event = AuditEvent.builder()
                    .action(AuditAction.CREATE)
                    .build();

            // Assert
            assertThat(event.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("should throw IllegalStateException when action is not set")
        void shouldThrowWhenActionNotSet() {
            // Act & Assert
            assertThatThrownBy(() -> AuditEvent.builder().build())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("action is required");
        }

        @Test
        @DisplayName("should initialize empty context map when not provided")
        void shouldInitializeEmptyContextMap() {
            // Act
            AuditEvent event = AuditEvent.builder()
                    .action(AuditAction.CREATE)
                    .build();

            // Assert
            assertThat(event.getContext()).isNotNull().isEmpty();
        }
    }

    @Nested
    @DisplayName("Builder addContext()")
    class AddContext {

        @Test
        @DisplayName("should add single context entry")
        void shouldAddSingleContextEntry() {
            // Act
            AuditEvent event = AuditEvent.builder()
                    .action(AuditAction.CREATE)
                    .addContext("key", "value")
                    .build();

            // Assert
            assertThat(event.getContext()).containsEntry("key", "value");
        }

        @Test
        @DisplayName("should add multiple context entries")
        void shouldAddMultipleContextEntries() {
            // Act
            AuditEvent event = AuditEvent.builder()
                    .action(AuditAction.CREATE)
                    .addContext("key1", "value1")
                    .addContext("key2", "value2")
                    .addContext("key3", "value3")
                    .build();

            // Assert
            assertThat(event.getContext())
                    .hasSize(3)
                    .containsEntry("key1", "value1")
                    .containsEntry("key2", "value2")
                    .containsEntry("key3", "value3");
        }

        @Test
        @DisplayName("should overwrite existing context key")
        void shouldOverwriteExistingContextKey() {
            // Act
            AuditEvent event = AuditEvent.builder()
                    .action(AuditAction.CREATE)
                    .addContext("key", "original")
                    .addContext("key", "updated")
                    .build();

            // Assert
            assertThat(event.getContext()).containsEntry("key", "updated");
        }
    }

    @Nested
    @DisplayName("Builder errorMessage()")
    class ErrorMessage {

        @Test
        @DisplayName("should set success to false when error message is provided")
        void shouldSetSuccessToFalseWhenErrorMessageProvided() {
            // Act
            AuditEvent event = AuditEvent.builder()
                    .action(AuditAction.WORKFLOW_FAILED)
                    .errorMessage("Something went wrong")
                    .build();

            // Assert
            assertThat(event.isSuccess()).isFalse();
            assertThat(event.getErrorMessage()).isEqualTo("Something went wrong");
        }

        @Test
        @DisplayName("should set success to true when error message is null")
        void shouldSetSuccessToTrueWhenErrorMessageIsNull() {
            // Act
            AuditEvent event = AuditEvent.builder()
                    .action(AuditAction.WORKFLOW_COMPLETED)
                    .errorMessage(null)
                    .build();

            // Assert
            assertThat(event.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("should set success to true when error message is empty")
        void shouldSetSuccessToTrueWhenErrorMessageIsEmpty() {
            // Act
            AuditEvent event = AuditEvent.builder()
                    .action(AuditAction.WORKFLOW_COMPLETED)
                    .errorMessage("")
                    .build();

            // Assert
            assertThat(event.isSuccess()).isTrue();
        }
    }

    @Nested
    @DisplayName("toBuilder()")
    class ToBuilder {

        @Test
        @DisplayName("should create builder with copied values")
        void shouldCreateBuilderWithCopiedValues() {
            // Arrange
            Instant now = Instant.now();
            AuditEvent original = AuditEvent.builder()
                    .id("original-id")
                    .timestamp(now)
                    .action(AuditAction.CREATE)
                    .userId("user-123")
                    .username("john.doe")
                    .resourceType("workflow")
                    .resourceId("wf-456")
                    .success(true)
                    .ipAddress("192.168.1.1")
                    .userAgent("Mozilla/5.0")
                    .sessionId("session-789")
                    .traceId("trace-abc")
                    .addContext("key", "value")
                    .build();

            // Act
            AuditEvent copy = original.toBuilder().build();

            // Assert
            assertThat(copy.getId()).isEqualTo(original.getId());
            assertThat(copy.getTimestamp()).isEqualTo(original.getTimestamp());
            assertThat(copy.getAction()).isEqualTo(original.getAction());
            assertThat(copy.getUserId()).isEqualTo(original.getUserId());
            assertThat(copy.getUsername()).isEqualTo(original.getUsername());
            assertThat(copy.getResourceType()).isEqualTo(original.getResourceType());
            assertThat(copy.getResourceId()).isEqualTo(original.getResourceId());
            assertThat(copy.isSuccess()).isEqualTo(original.isSuccess());
            assertThat(copy.getIpAddress()).isEqualTo(original.getIpAddress());
            assertThat(copy.getUserAgent()).isEqualTo(original.getUserAgent());
            assertThat(copy.getSessionId()).isEqualTo(original.getSessionId());
            assertThat(copy.getTraceId()).isEqualTo(original.getTraceId());
            assertThat(copy.getContext()).containsEntry("key", "value");
        }

        @Test
        @DisplayName("should allow overriding fields on copied builder")
        void shouldAllowOverridingFieldsOnCopiedBuilder() {
            // Arrange
            AuditEvent original = AuditEvent.builder()
                    .action(AuditAction.CREATE)
                    .userId("user-123")
                    .resourceType("workflow")
                    .build();

            // Act
            AuditEvent modified = original.toBuilder()
                    .action(AuditAction.UPDATE)
                    .userId("user-456")
                    .build();

            // Assert
            assertThat(modified.getAction()).isEqualTo(AuditAction.UPDATE);
            assertThat(modified.getUserId()).isEqualTo("user-456");
            assertThat(modified.getResourceType()).isEqualTo("workflow");
        }

        @Test
        @DisplayName("should create independent context map in copy")
        void shouldCreateIndependentContextMapInCopy() {
            // Arrange
            AuditEvent original = AuditEvent.builder()
                    .action(AuditAction.CREATE)
                    .addContext("key", "value")
                    .build();

            // Act
            AuditEvent copy = original.toBuilder()
                    .addContext("newKey", "newValue")
                    .build();

            // Assert
            assertThat(original.getContext()).doesNotContainKey("newKey");
            assertThat(copy.getContext()).containsEntry("key", "value");
            assertThat(copy.getContext()).containsEntry("newKey", "newValue");
        }
    }

    @Nested
    @DisplayName("toString()")
    class ToStringTests {

        @Test
        @DisplayName("should contain key fields in string representation")
        void shouldContainKeyFieldsInStringRepresentation() {
            // Arrange
            AuditEvent event = AuditEvent.builder()
                    .id("test-id")
                    .action(AuditAction.WORKFLOW_EXECUTE)
                    .userId("user-123")
                    .username("john.doe")
                    .resourceType("workflow")
                    .resourceId("wf-456")
                    .success(true)
                    .build();

            // Act
            String result = event.toString();

            // Assert
            assertThat(result)
                    .contains("test-id")
                    .contains("WORKFLOW_EXECUTE")
                    .contains("user-123")
                    .contains("john.doe")
                    .contains("workflow")
                    .contains("wf-456")
                    .contains("success=true");
        }
    }
}
