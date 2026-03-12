package br.com.archflow.conversation.service;

import br.com.archflow.conversation.ConversationManager;
import br.com.archflow.conversation.form.FormData;
import br.com.archflow.conversation.message.ConversationMessage;
import br.com.archflow.conversation.state.SuspendedConversation;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DefaultConversationService")
class DefaultConversationServiceTest {

    private ConversationManager manager;
    private DefaultConversationService service;

    @BeforeEach
    void setUp() {
        ConversationManager.reset();
        manager = ConversationManager.getInstance(Duration.ofMinutes(30));
        service = new DefaultConversationService(manager);
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

    @Test
    @DisplayName("should suspend conversation")
    void shouldSuspendConversation() {
        // Act
        SuspendedConversation suspended = service.suspend("conv-1", "wf-1", sampleForm());

        // Assert
        assertThat(suspended).isNotNull();
        assertThat(suspended.getConversationId()).isEqualTo("conv-1");
        assertThat(suspended.getWorkflowId()).isEqualTo("wf-1");
        assertThat(suspended.getResumeToken()).isNotBlank();
        assertThat(suspended.isActive()).isTrue();
    }

    @Test
    @DisplayName("should resume conversation")
    void shouldResumeConversation() {
        // Arrange
        SuspendedConversation suspended = service.suspend("conv-1", "wf-1", sampleForm());

        // Act
        Optional<SuspendedConversation> resumed = service.resume(
                suspended.getResumeToken(), Map.of("name", "John"));

        // Assert
        assertThat(resumed).isPresent();
        assertThat(resumed.get().getStatus())
                .isEqualTo(SuspendedConversation.ConversationStatus.RESUMED);
    }

    @Test
    @DisplayName("should add message")
    void shouldAddMessage() {
        // Arrange
        ConversationMessage message = ConversationMessage.user("conv-1", "Hello");

        // Act
        service.addMessage(message);

        // Assert
        List<ConversationMessage> messages = service.getMessages("conv-1");
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).content()).isEqualTo("Hello");
        assertThat(messages.get(0).role()).isEqualTo(ConversationMessage.Role.USER);
    }

    @Test
    @DisplayName("should get messages")
    void shouldGetMessages() {
        // Arrange
        service.addMessage(ConversationMessage.user("conv-1", "Hello"));
        service.addMessage(ConversationMessage.assistant("conv-1", "Hi there!"));

        // Act
        List<ConversationMessage> messages = service.getMessages("conv-1");

        // Assert
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).role()).isEqualTo(ConversationMessage.Role.USER);
        assertThat(messages.get(1).role()).isEqualTo(ConversationMessage.Role.ASSISTANT);
    }

    @Test
    @DisplayName("should return empty for unknown conversation")
    void shouldReturnEmptyForUnknownConversation() {
        // Act
        Optional<SuspendedConversation> result = service.getById("unknown");

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should track messages on suspend (system message auto-added)")
    void shouldTrackMessagesOnSuspend() {
        // Act
        service.suspend("conv-1", "wf-1", sampleForm());

        // Assert
        List<ConversationMessage> messages = service.getMessages("conv-1");
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).role()).isEqualTo(ConversationMessage.Role.SYSTEM);
        assertThat(messages.get(0).content()).contains("suspended");
    }

    @Test
    @DisplayName("should track messages on resume (user message auto-added)")
    void shouldTrackMessagesOnResume() {
        // Arrange
        SuspendedConversation suspended = service.suspend("conv-1", "wf-1", sampleForm());

        // Act
        service.resume(suspended.getResumeToken(), Map.of("name", "John"));

        // Assert
        List<ConversationMessage> messages = service.getMessages("conv-1");
        assertThat(messages).hasSize(2);
        // First message is the system suspend message
        assertThat(messages.get(0).role()).isEqualTo(ConversationMessage.Role.SYSTEM);
        // Second message is the user resume message
        assertThat(messages.get(1).role()).isEqualTo(ConversationMessage.Role.USER);
        assertThat(messages.get(1).content()).contains("name=John");
    }

    @Test
    @DisplayName("should cancel conversation")
    void shouldCancelConversation() {
        // Arrange
        service.suspend("conv-1", "wf-1", sampleForm());

        // Act
        boolean cancelled = service.cancel("conv-1");

        // Assert
        assertThat(cancelled).isTrue();
        assertThat(service.getById("conv-1")).isEmpty();
    }

    @Test
    @DisplayName("should get active conversations")
    void shouldGetActiveConversations() {
        // Arrange
        service.suspend("conv-1", "wf-1", sampleForm());
        service.suspend("conv-2", "wf-2", sampleForm());

        // Act
        List<SuspendedConversation> active = service.getActiveConversations();

        // Assert
        assertThat(active).hasSize(2);
    }

    @Test
    @DisplayName("should cleanup expired")
    void shouldCleanupExpired() {
        // Arrange - suspend with very short timeout
        service.suspend("conv-1", "wf-1", "exec-1", sampleForm(),
                Duration.ofMillis(1), Map.of());

        // Wait for expiration
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Act
        int cleaned = service.cleanupExpired();

        // Assert
        assertThat(cleaned).isEqualTo(1);
    }

    @Test
    @DisplayName("should get by token")
    void shouldGetByToken() {
        // Arrange
        SuspendedConversation suspended = service.suspend("conv-1", "wf-1", sampleForm());

        // Act
        Optional<SuspendedConversation> found = service.getByToken(suspended.getResumeToken());

        // Assert
        assertThat(found).isPresent();
        assertThat(found.get().getConversationId()).isEqualTo("conv-1");
    }

    @Test
    @DisplayName("should get by id")
    void shouldGetById() {
        // Arrange
        service.suspend("conv-1", "wf-1", sampleForm());

        // Act
        Optional<SuspendedConversation> found = service.getById("conv-1");

        // Assert
        assertThat(found).isPresent();
        assertThat(found.get().getWorkflowId()).isEqualTo("wf-1");
    }

    @Test
    @DisplayName("should return empty on invalid token")
    void shouldReturnEmptyOnInvalidToken() {
        // Act
        Optional<SuspendedConversation> result = service.resume("invalid-token", Map.of());

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should suspend with full options")
    void shouldSuspendWithFullOptions() {
        // Act
        SuspendedConversation suspended = service.suspend(
                "conv-1", "wf-1", "exec-1", sampleForm(),
                Duration.ofMinutes(60), Map.of("key", "value"));

        // Assert
        assertThat(suspended).isNotNull();
        assertThat(suspended.getConversationId()).isEqualTo("conv-1");
        assertThat(suspended.getWorkflowId()).isEqualTo("wf-1");
        assertThat(suspended.getWorkflowExecutionId()).isEqualTo("exec-1");
        assertThat(suspended.getContext()).containsEntry("key", "value");
    }

    @Test
    @DisplayName("should get empty messages for new conversation")
    void shouldGetEmptyMessagesForNewConversation() {
        // Act
        List<ConversationMessage> messages = service.getMessages("new-conv");

        // Assert
        assertThat(messages).isEmpty();
    }
}
