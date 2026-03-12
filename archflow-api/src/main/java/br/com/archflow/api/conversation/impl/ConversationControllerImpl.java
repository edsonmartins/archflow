package br.com.archflow.api.conversation.impl;

import br.com.archflow.api.conversation.ConversationController;
import br.com.archflow.api.conversation.dto.ConversationResponse;
import br.com.archflow.api.conversation.dto.ConversationResponse.MessageResponse;
import br.com.archflow.api.conversation.dto.ResumeRequest;
import br.com.archflow.api.conversation.dto.SuspendRequest;
import br.com.archflow.conversation.form.FormData;
import br.com.archflow.conversation.message.ConversationMessage;
import br.com.archflow.conversation.service.ConversationService;
import br.com.archflow.conversation.state.SuspendedConversation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Default implementation of {@link ConversationController}.
 *
 * <p>This implementation delegates to {@link ConversationService} for all conversation operations.
 * It can be used directly or wrapped by framework-specific adapters (Spring, etc.).</p>
 */
public class ConversationControllerImpl implements ConversationController {

    private static final Logger log = LoggerFactory.getLogger(ConversationControllerImpl.class);
    private static final int DEFAULT_TIMEOUT_MINUTES = 30;

    private final ConversationService conversationService;

    /**
     * Creates a new ConversationControllerImpl.
     *
     * @param conversationService The conversation service
     */
    public ConversationControllerImpl(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @Override
    public ConversationResponse suspend(SuspendRequest request) {
        log.info("Suspending conversation: {} for workflow: {}", request.conversationId(), request.workflowId());

        int timeoutMinutes = request.timeoutMinutes() != null ? request.timeoutMinutes() : DEFAULT_TIMEOUT_MINUTES;

        SuspendedConversation suspended = conversationService.suspend(
                request.conversationId(),
                request.workflowId(),
                request.executionId(),
                null, // Form resolved by service/manager
                Duration.ofMinutes(timeoutMinutes),
                Map.of()
        );

        List<ConversationMessage> messages = conversationService.getMessages(request.conversationId());

        return toResponse(suspended, messages);
    }

    @Override
    public ConversationResponse resume(ResumeRequest request) {
        log.info("Resuming conversation with token: {}", request.resumeToken());

        SuspendedConversation resumed = conversationService.resume(request.resumeToken(), request.formData())
                .orElseThrow(() -> new NotFoundException(
                        "Conversation not found or expired for token: " + request.resumeToken()));

        List<ConversationMessage> messages = conversationService.getMessages(resumed.getConversationId());

        return toResponse(resumed, messages);
    }

    @Override
    public ConversationResponse getConversation(String conversationId) {
        log.debug("Getting conversation: {}", conversationId);

        SuspendedConversation conversation = conversationService.getById(conversationId)
                .orElseThrow(() -> new NotFoundException("Conversation not found: " + conversationId));

        List<ConversationMessage> messages = conversationService.getMessages(conversationId);

        return toResponse(conversation, messages);
    }

    @Override
    public ConversationResponse getMessages(String conversationId) {
        log.debug("Getting messages for conversation: {}", conversationId);

        SuspendedConversation conversation = conversationService.getById(conversationId)
                .orElse(null);

        List<ConversationMessage> messages = conversationService.getMessages(conversationId);

        if (conversation != null) {
            return toResponse(conversation, messages);
        }

        // Return messages-only response when conversation is no longer tracked
        return new ConversationResponse(
                conversationId,
                "UNKNOWN",
                null,
                null,
                null,
                messages.stream().map(this::toMessageResponse).toList()
        );
    }

    @Override
    public void cancel(String conversationId) {
        log.info("Cancelling conversation: {}", conversationId);

        boolean cancelled = conversationService.cancel(conversationId);
        if (!cancelled) {
            throw new NotFoundException("Conversation not found: " + conversationId);
        }
    }

    /**
     * Converts a SuspendedConversation and its messages to a ConversationResponse.
     */
    private ConversationResponse toResponse(SuspendedConversation conversation,
                                             List<ConversationMessage> messages) {
        return new ConversationResponse(
                conversation.getConversationId(),
                conversation.getStatus().name(),
                conversation.getResumeToken(),
                conversation.getForm(),
                conversation.getExpiresAt(),
                messages.stream().map(this::toMessageResponse).toList()
        );
    }

    /**
     * Converts a ConversationMessage to a MessageResponse DTO.
     */
    private MessageResponse toMessageResponse(ConversationMessage message) {
        return new MessageResponse(
                message.id(),
                message.role().name(),
                message.content(),
                message.timestamp(),
                message.metadata()
        );
    }
}
