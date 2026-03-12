package br.com.archflow.conversation;

import br.com.archflow.conversation.event.ArchflowEvent;
import br.com.archflow.conversation.form.FormData;
import br.com.archflow.conversation.state.SuspendedConversation;
import br.com.archflow.conversation.state.SuspendedConversation.ConversationStatus;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConversationManager")
class ConversationManagerTest {

    private ConversationManager manager;

    @BeforeEach
    void setUp() {
        ConversationManager.reset();
        manager = ConversationManager.getInstance(Duration.ofMinutes(30));
    }

    @AfterEach
    void tearDown() {
        ConversationManager.reset();
    }

    private FormData sampleForm() {
        return FormData.builder()
                .title("Test Form")
                .addField(FormData.FormField.text("name", "Name").build())
                .build();
    }

    @Nested
    @DisplayName("Suspend")
    class SuspendTests {

        @Test
        @DisplayName("should suspend conversation and return suspended state")
        void shouldSuspendConversation() {
            // Act
            SuspendedConversation suspended = manager.suspend("conv-1", "wf-1", sampleForm());

            // Assert
            assertThat(suspended).isNotNull();
            assertThat(suspended.getConversationId()).isEqualTo("conv-1");
            assertThat(suspended.getWorkflowId()).isEqualTo("wf-1");
            assertThat(suspended.getResumeToken()).isNotNull().isNotEmpty();
            assertThat(suspended.getStatus()).isEqualTo(ConversationStatus.WAITING);
            assertThat(suspended.getExpiresAt()).isNotNull();
            assertThat(suspended.getForm()).isNotNull();
        }

        @Test
        @DisplayName("should suspend with full options")
        void shouldSuspendWithFullOptions() {
            // Arrange
            Map<String, Object> context = Map.of("step", "validation");

            // Act
            SuspendedConversation suspended = manager.suspend(
                    "conv-1", "wf-1", "exec-1", sampleForm(),
                    Duration.ofMinutes(10), context
            );

            // Assert
            assertThat(suspended.getWorkflowExecutionId()).isEqualTo("exec-1");
            assertThat(suspended.getContext()).containsEntry("step", "validation");
        }

        @Test
        @DisplayName("should generate unique resume tokens")
        void shouldGenerateUniqueTokens() {
            SuspendedConversation s1 = manager.suspend("conv-1", "wf-1", sampleForm());
            SuspendedConversation s2 = manager.suspend("conv-2", "wf-1", sampleForm());

            assertThat(s1.getResumeToken()).isNotEqualTo(s2.getResumeToken());
        }
    }

    @Nested
    @DisplayName("Resume")
    class ResumeTests {

        @Test
        @DisplayName("should resume conversation with form data")
        void shouldResumeConversation() {
            // Arrange
            SuspendedConversation suspended = manager.suspend("conv-1", "wf-1", sampleForm());
            Map<String, Object> formData = Map.of("name", "John Doe");

            // Act
            Optional<SuspendedConversation> resumed = manager.resume(
                    suspended.getResumeToken(), formData
            );

            // Assert
            assertThat(resumed).isPresent();
            assertThat(resumed.get().getStatus()).isEqualTo(ConversationStatus.RESUMED);
            assertThat(resumed.get().getContext()).containsKey("formData");
        }

        @Test
        @DisplayName("should return empty for invalid token")
        void shouldReturnEmptyForInvalidToken() {
            Optional<SuspendedConversation> result = manager.resume("invalid-token", Map.of());
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty for expired conversation")
        void shouldReturnEmptyForExpired() {
            // Arrange: suspend with a very short timeout (already expired)
            SuspendedConversation suspended = manager.suspend(
                    "conv-1", "wf-1", null, sampleForm(),
                    Duration.ofNanos(1), null
            );

            // Small delay to ensure expiration
            try { Thread.sleep(10); } catch (InterruptedException ignored) {}

            // Act
            Optional<SuspendedConversation> result = manager.resume(
                    suspended.getResumeToken(), Map.of("name", "John")
            );

            // Assert
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("GetByToken")
    class GetByTokenTests {

        @Test
        @DisplayName("should return conversation by token")
        void shouldReturnByToken() {
            SuspendedConversation suspended = manager.suspend("conv-1", "wf-1", sampleForm());

            Optional<SuspendedConversation> result = manager.getByToken(suspended.getResumeToken());

            assertThat(result).isPresent();
            assertThat(result.get().getConversationId()).isEqualTo("conv-1");
        }

        @Test
        @DisplayName("should return empty for unknown token")
        void shouldReturnEmptyForUnknownToken() {
            Optional<SuspendedConversation> result = manager.getByToken("unknown");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty and cleanup expired conversation")
        void shouldCleanupExpiredOnGet() {
            // Arrange
            SuspendedConversation suspended = manager.suspend(
                    "conv-1", "wf-1", null, sampleForm(),
                    Duration.ofNanos(1), null
            );
            try { Thread.sleep(10); } catch (InterruptedException ignored) {}

            // Act
            Optional<SuspendedConversation> result = manager.getByToken(suspended.getResumeToken());

            // Assert
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("GetById")
    class GetByIdTests {

        @Test
        @DisplayName("should return conversation by ID")
        void shouldReturnById() {
            manager.suspend("conv-1", "wf-1", sampleForm());

            Optional<SuspendedConversation> result = manager.getById("conv-1");

            assertThat(result).isPresent();
            assertThat(result.get().getConversationId()).isEqualTo("conv-1");
        }

        @Test
        @DisplayName("should return empty for unknown ID")
        void shouldReturnEmptyForUnknownId() {
            assertThat(manager.getById("unknown")).isEmpty();
        }
    }

    @Nested
    @DisplayName("Cancel")
    class CancelTests {

        @Test
        @DisplayName("should cancel existing conversation")
        void shouldCancelConversation() {
            // Arrange
            manager.suspend("conv-1", "wf-1", sampleForm());

            // Act
            boolean result = manager.cancel("conv-1");

            // Assert
            assertThat(result).isTrue();
            assertThat(manager.getById("conv-1")).isEmpty();
        }

        @Test
        @DisplayName("should return false for non-existent conversation")
        void shouldReturnFalseForNonExistent() {
            assertThat(manager.cancel("non-existent")).isFalse();
        }
    }

    @Nested
    @DisplayName("Complete")
    class CompleteTests {

        @Test
        @DisplayName("should complete and remove conversation")
        void shouldCompleteConversation() {
            // Arrange
            SuspendedConversation suspended = manager.suspend("conv-1", "wf-1", sampleForm());

            // Act
            boolean result = manager.complete("conv-1");

            // Assert
            assertThat(result).isTrue();
            assertThat(manager.getById("conv-1")).isEmpty();
            assertThat(manager.getByToken(suspended.getResumeToken())).isEmpty();
        }

        @Test
        @DisplayName("should return false for non-existent conversation")
        void shouldReturnFalseForNonExistent() {
            assertThat(manager.complete("non-existent")).isFalse();
        }
    }

    @Nested
    @DisplayName("Expired Conversations")
    class ExpiredTests {

        @Test
        @DisplayName("cleanupExpired should remove expired conversations")
        void cleanupExpiredShouldRemove() {
            // Arrange
            manager.suspend("conv-1", "wf-1", null, sampleForm(), Duration.ofNanos(1), null);
            manager.suspend("conv-2", "wf-1", sampleForm()); // not expired
            try { Thread.sleep(10); } catch (InterruptedException ignored) {}

            // Act
            int cleaned = manager.cleanupExpired();

            // Assert
            assertThat(cleaned).isEqualTo(1);
            assertThat(manager.getById("conv-1")).isEmpty();
            assertThat(manager.getById("conv-2")).isPresent();
        }

        @Test
        @DisplayName("cleanupExpired should return 0 when nothing expired")
        void cleanupExpiredShouldReturnZeroWhenNothingExpired() {
            manager.suspend("conv-1", "wf-1", sampleForm());
            assertThat(manager.cleanupExpired()).isZero();
        }

        @Test
        @DisplayName("createCleanupTask should return runnable that cleans expired")
        void createCleanupTaskShouldWork() {
            // Arrange
            manager.suspend("conv-1", "wf-1", null, sampleForm(), Duration.ofNanos(1), null);
            try { Thread.sleep(10); } catch (InterruptedException ignored) {}

            // Act
            Runnable task = manager.createCleanupTask();
            task.run();

            // Assert
            assertThat(manager.getById("conv-1")).isEmpty();
        }
    }

    @Nested
    @DisplayName("Stats")
    class StatsTests {

        @Test
        @DisplayName("should return correct stats for mixed states")
        void shouldReturnCorrectStats() {
            // Arrange
            manager.suspend("conv-1", "wf-1", sampleForm());
            manager.suspend("conv-2", "wf-1", sampleForm());
            SuspendedConversation s3 = manager.suspend("conv-3", "wf-1", sampleForm());
            manager.resume(s3.getResumeToken(), Map.of("name", "test"));

            // Act
            ConversationManager.ConversationStats stats = manager.getStats();

            // Assert
            assertThat(stats.total()).isEqualTo(3);
            assertThat(stats.waiting()).isEqualTo(2);
            assertThat(stats.resumed()).isEqualTo(1);
        }

        @Test
        @DisplayName("should return zero stats when empty")
        void shouldReturnZeroStatsWhenEmpty() {
            ConversationManager.ConversationStats stats = manager.getStats();

            assertThat(stats.total()).isZero();
            assertThat(stats.active()).isZero();
            assertThat(stats.waiting()).isZero();
            assertThat(stats.resumed()).isZero();
            assertThat(stats.cancelled()).isZero();
            assertThat(stats.timedOut()).isZero();
        }
    }

    @Nested
    @DisplayName("Active Conversations")
    class ActiveConversationsTests {

        @Test
        @DisplayName("getActiveCount should return count of WAITING non-expired conversations")
        void getActiveCountShouldReturnCorrectCount() {
            // Arrange
            manager.suspend("conv-1", "wf-1", sampleForm());
            manager.suspend("conv-2", "wf-1", sampleForm());
            SuspendedConversation s3 = manager.suspend("conv-3", "wf-1", sampleForm());
            manager.resume(s3.getResumeToken(), Map.of("name", "test"));

            // Act & Assert
            assertThat(manager.getActiveCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("getActiveConversations should return list of active conversations")
        void getActiveConversationsShouldReturnList() {
            // Arrange
            manager.suspend("conv-1", "wf-1", sampleForm());
            manager.suspend("conv-2", "wf-1", sampleForm());

            // Act
            List<SuspendedConversation> active = manager.getActiveConversations();

            // Assert
            assertThat(active).hasSize(2);
            assertThat(active).allMatch(SuspendedConversation::isActive);
        }
    }

    @Nested
    @DisplayName("Event Subscription")
    class EventSubscriptionTests {

        @Test
        @DisplayName("subscriber should receive suspend event")
        void subscriberShouldReceiveSuspendEvent() {
            // Arrange
            List<ArchflowEvent> receivedEvents = new ArrayList<>();
            manager.subscribe("test-subscriber", receivedEvents::add);

            // Act
            manager.suspend("conv-1", "wf-1", sampleForm());

            // Assert
            assertThat(receivedEvents).hasSize(1);
            assertThat(receivedEvents.get(0).getEnvelope().type()).isEqualTo("suspend");
        }

        @Test
        @DisplayName("subscriber should receive resume event")
        void subscriberShouldReceiveResumeEvent() {
            // Arrange
            List<ArchflowEvent> receivedEvents = new ArrayList<>();
            SuspendedConversation suspended = manager.suspend("conv-1", "wf-1", sampleForm());
            manager.subscribe("test-subscriber", receivedEvents::add);

            // Act
            manager.resume(suspended.getResumeToken(), Map.of("name", "John"));

            // Assert
            assertThat(receivedEvents).hasSize(1);
            assertThat(receivedEvents.get(0).getEnvelope().type()).isEqualTo("resume");
        }

        @Test
        @DisplayName("unsubscribe should stop receiving events")
        void unsubscribeShouldStopReceivingEvents() {
            // Arrange
            List<ArchflowEvent> receivedEvents = new ArrayList<>();
            manager.subscribe("test-subscriber", receivedEvents::add);
            manager.unsubscribe("test-subscriber");

            // Act
            manager.suspend("conv-1", "wf-1", sampleForm());

            // Assert
            assertThat(receivedEvents).isEmpty();
        }

        @Test
        @DisplayName("failing subscriber should not prevent other subscribers from receiving events")
        void failingSubscriberShouldNotAffectOthers() {
            // Arrange
            List<ArchflowEvent> receivedEvents = new ArrayList<>();
            manager.subscribe("failing-subscriber", event -> {
                throw new RuntimeException("Subscriber error");
            });
            manager.subscribe("good-subscriber", receivedEvents::add);

            // Act
            manager.suspend("conv-1", "wf-1", sampleForm());

            // Assert
            assertThat(receivedEvents).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Singleton")
    class SingletonTests {

        @Test
        @DisplayName("getInstance should return same instance")
        void getInstanceShouldReturnSameInstance() {
            ConversationManager m1 = ConversationManager.getInstance();
            ConversationManager m2 = ConversationManager.getInstance();

            assertThat(m1).isSameAs(m2);
        }

        @Test
        @DisplayName("reset should clear the singleton")
        void resetShouldClearSingleton() {
            ConversationManager m1 = ConversationManager.getInstance();
            ConversationManager.reset();
            ConversationManager m2 = ConversationManager.getInstance();

            assertThat(m1).isNotSameAs(m2);
        }
    }
}
