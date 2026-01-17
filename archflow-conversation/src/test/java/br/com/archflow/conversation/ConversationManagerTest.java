package br.com.archflow.conversation;

import br.com.archflow.conversation.event.ArchflowEvent;
import br.com.archflow.conversation.form.FormData;
import br.com.archflow.conversation.state.SuspendedConversation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ConversationManager}.
 */
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

    @Test
    void testSuspendConversation() {
        SuspendedConversation suspended = manager.suspend(
                "conv-123",
                "workflow-456",
                FormData.Templates.userRegistration()
        );

        assertThat(suspended.getConversationId()).isEqualTo("conv-123");
        assertThat(suspended.getWorkflowId()).isEqualTo("workflow-456");
        assertThat(suspended.getResumeToken()).isNotNull();
        assertThat(suspended.getForm()).isNotNull();
        assertThat(suspended.isActive()).isTrue();
    }

    @Test
    void testResumeConversation() {
        SuspendedConversation suspended = manager.suspend(
                "conv-123",
                "workflow-456",
                FormData.Templates.userRegistration()
        );

        Map<String, Object> formData = Map.of(
                "name", "John Doe",
                "email", "john@example.com",
                "password", "securepass123",
                "terms", true
        );

        Optional<SuspendedConversation> resumed = manager.resume(suspended.getResumeToken(), formData);

        assertThat(resumed).isPresent();
        assertThat(resumed.get().getStatus()).isEqualTo(SuspendedConversation.ConversationStatus.RESUMED);
    }

    @Test
    void testResumeWithInvalidToken() {
        Optional<SuspendedConversation> resumed = manager.resume("invalid-token", Map.of());

        assertThat(resumed).isEmpty();
    }

    @Test
    void testGetByToken() {
        SuspendedConversation suspended = manager.suspend(
                "conv-123",
                "workflow-456",
                FormData.Templates.userRegistration()
        );

        Optional<SuspendedConversation> found = manager.getByToken(suspended.getResumeToken());

        assertThat(found).isPresent();
        assertThat(found.get().getConversationId()).isEqualTo("conv-123");
    }

    @Test
    void testGetById() {
        SuspendedConversation suspended = manager.suspend(
                "conv-123",
                "workflow-456",
                FormData.Templates.userRegistration()
        );

        Optional<SuspendedConversation> found = manager.getById("conv-123");

        assertThat(found).isPresent();
        assertThat(found.get().getResumeToken()).isEqualTo(suspended.getResumeToken());
    }

    @Test
    void testCancelConversation() {
        manager.suspend("conv-123", "workflow-456", FormData.Templates.userRegistration());

        boolean cancelled = manager.cancel("conv-123");

        assertThat(cancelled).isTrue();
        assertThat(manager.getById("conv-123")).isEmpty();
    }

    @Test
    void testCompleteConversation() {
        SuspendedConversation suspended = manager.suspend(
                "conv-123",
                "workflow-456",
                FormData.Templates.userRegistration()
        );

        boolean completed = manager.complete("conv-123");

        assertThat(completed).isTrue();
        assertThat(manager.getById("conv-123")).isEmpty();
    }

    @Test
    void testGetActiveCount() {
        manager.suspend("conv-1", "workflow-1", FormData.Templates.userRegistration());
        manager.suspend("conv-2", "workflow-2", FormData.Templates.customerSupport());

        assertThat(manager.getActiveCount()).isEqualTo(2);

        manager.cancel("conv-1");

        assertThat(manager.getActiveCount()).isEqualTo(1);
    }

    @Test
    void testGetStats() {
        manager.suspend("conv-1", "workflow-1", FormData.Templates.userRegistration());
        manager.suspend("conv-2", "workflow-2", FormData.Templates.customerSupport());

        ConversationManager.ConversationStats stats = manager.getStats();

        assertThat(stats.total()).isEqualTo(2);
        assertThat(stats.active()).isEqualTo(2);
        assertThat(stats.waiting()).isEqualTo(2);
    }

    @Test
    void testEventSubscription() {
        AtomicInteger eventCount = new AtomicInteger(0);
        manager.subscribe("test-subscriber", event -> eventCount.incrementAndGet());

        manager.suspend("conv-123", "workflow-456", FormData.Templates.userRegistration());

        assertThat(eventCount.get()).isGreaterThan(0);

        manager.unsubscribe("test-subscriber");
    }

    @Test
    void testSuspendedConversationExpiration() {
        SuspendedConversation suspended = SuspendedConversation.builder()
                .conversationId("conv-123")
                .resumeToken("token-123")
                .workflowId("workflow-456")
                .form(FormData.Templates.userRegistration())
                .expiresAt(java.time.Instant.now().minusSeconds(60))
                .build();

        assertThat(suspended.isExpired()).isTrue();
        assertThat(suspended.isActive()).isFalse();
    }

    @Test
    void testSuspendedConversationResumeMethod() {
        SuspendedConversation original = SuspendedConversation.builder()
                .conversationId("conv-123")
                .resumeToken("token-123")
                .workflowId("workflow-456")
                .form(FormData.Templates.userRegistration())
                .build();

        Map<String, Object> formData = Map.of("name", "John");
        SuspendedConversation resumed = original.resume(formData);

        assertThat(resumed.getStatus()).isEqualTo(SuspendedConversation.ConversationStatus.RESUMED);
        assertThat(resumed.getContext()).containsKey("formData");
    }

    @Test
    void testSuspendedConversationCancelMethod() {
        SuspendedConversation original = SuspendedConversation.builder()
                .conversationId("conv-123")
                .resumeToken("token-123")
                .workflowId("workflow-456")
                .form(FormData.Templates.userRegistration())
                .build();

        SuspendedConversation cancelled = original.cancel();

        assertThat(cancelled.getStatus()).isEqualTo(SuspendedConversation.ConversationStatus.CANCELLED);
    }

    @Test
    void testArchflowEventCreation() {
        ArchflowEvent chatEvent = ArchflowEvent.chatMessage("Hello, world!");
        assertThat(chatEvent.getEnvelope().domain()).isEqualTo(ArchflowEvent.EventDomain.CHAT);
        assertThat(chatEvent.getEnvelope().type()).isEqualTo(ArchflowEvent.EventType.MESSAGE);

        ArchflowEvent deltaEvent = ArchflowEvent.chatDelta("Hello");
        assertThat(deltaEvent.getEnvelope().type()).isEqualTo(ArchflowEvent.EventType.DELTA);

        FormData form = FormData.Templates.userRegistration();
        ArchflowEvent suspendEvent = ArchflowEvent.suspend("conv-123", "token-456", form);
        assertThat(suspendEvent.getEnvelope().domain()).isEqualTo(ArchflowEvent.EventDomain.INTERACTION);
        assertThat(suspendEvent.getEnvelope().type()).isEqualTo(ArchflowEvent.EventType.SUSPEND);

        ArchflowEvent toolEvent = ArchflowEvent.toolStart("search", Map.of("query", "test"));
        assertThat(toolEvent.getEnvelope().domain()).isEqualTo(ArchflowEvent.EventDomain.TOOL);

        ArchflowEvent errorEvent = ArchflowEvent.error("Something went wrong", new RuntimeException("test"));
        assertThat(errorEvent.getEnvelope().domain()).isEqualTo(ArchflowEvent.EventDomain.AUDIT);
        assertThat(errorEvent.getEnvelope().type()).isEqualTo(ArchflowEvent.EventType.ERROR);
    }
}
