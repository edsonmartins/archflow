package br.com.archflow.conversation.service;

import br.com.archflow.conversation.form.FormData;
import br.com.archflow.conversation.message.ConversationMessage;
import br.com.archflow.conversation.state.SuspendedConversation;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service layer for conversation management.
 * Wraps ConversationManager with message tracking and additional business logic.
 */
public interface ConversationService {

    SuspendedConversation suspend(String conversationId, String workflowId, FormData form);

    SuspendedConversation suspend(String conversationId, String workflowId,
                                   String executionId, FormData form, Duration timeout, Map<String, Object> context);

    Optional<SuspendedConversation> resume(String resumeToken, Map<String, Object> formData);

    Optional<SuspendedConversation> getByToken(String resumeToken);

    Optional<SuspendedConversation> getById(String conversationId);

    boolean cancel(String conversationId);

    void addMessage(ConversationMessage message);

    List<ConversationMessage> getMessages(String conversationId);

    List<SuspendedConversation> getActiveConversations();

    int cleanupExpired();
}
