package br.com.archflow.conversation.service;

import br.com.archflow.conversation.ConversationManager;
import br.com.archflow.conversation.form.FormData;
import br.com.archflow.conversation.message.ConversationMessage;
import br.com.archflow.conversation.state.SuspendedConversation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Default implementation of {@link ConversationService}.
 *
 * <p>Delegates suspend/resume logic to {@link ConversationManager} and adds
 * message tracking with automatic system/user messages on lifecycle events.</p>
 */
public class DefaultConversationService implements ConversationService {

    private static final Logger log = LoggerFactory.getLogger(DefaultConversationService.class);

    private final ConversationManager conversationManager;
    private final Map<String, List<ConversationMessage>> messageStore;

    /**
     * Creates a new DefaultConversationService.
     *
     * @param conversationManager The conversation manager to delegate to
     */
    public DefaultConversationService(ConversationManager conversationManager) {
        this.conversationManager = conversationManager;
        this.messageStore = new ConcurrentHashMap<>();
    }

    @Override
    public SuspendedConversation suspend(String conversationId, String workflowId, FormData form) {
        SuspendedConversation suspended = conversationManager.suspend(conversationId, workflowId, form);

        addMessage(ConversationMessage.system(conversationId, "Conversation suspended - awaiting user input"));

        log.debug("Suspended conversation {} via service layer", conversationId);
        return suspended;
    }

    @Override
    public SuspendedConversation suspend(String conversationId, String workflowId,
                                          String executionId, FormData form, Duration timeout,
                                          Map<String, Object> context) {
        SuspendedConversation suspended = conversationManager.suspend(
                conversationId, workflowId, executionId, form, timeout, context);

        addMessage(ConversationMessage.system(conversationId, "Conversation suspended - awaiting user input"));

        log.debug("Suspended conversation {} with full options via service layer", conversationId);
        return suspended;
    }

    @Override
    public Optional<SuspendedConversation> resume(String resumeToken, Map<String, Object> formData) {
        Optional<SuspendedConversation> resumed = conversationManager.resume(resumeToken, formData);

        resumed.ifPresent(conversation -> {
            String summary = buildFormDataSummary(formData);
            addMessage(ConversationMessage.user(conversation.getConversationId(),
                    "Form submitted: " + summary));

            log.debug("Resumed conversation {} via service layer", conversation.getConversationId());
        });

        return resumed;
    }

    @Override
    public Optional<SuspendedConversation> getByToken(String resumeToken) {
        return conversationManager.getByToken(resumeToken);
    }

    @Override
    public Optional<SuspendedConversation> getById(String conversationId) {
        return conversationManager.getById(conversationId);
    }

    @Override
    public boolean cancel(String conversationId) {
        boolean cancelled = conversationManager.cancel(conversationId);
        if (cancelled) {
            addMessage(ConversationMessage.system(conversationId, "Conversation cancelled"));
            log.debug("Cancelled conversation {} via service layer", conversationId);
        }
        return cancelled;
    }

    @Override
    public void addMessage(ConversationMessage message) {
        messageStore.computeIfAbsent(message.conversationId(), k -> new CopyOnWriteArrayList<>())
                .add(message);
    }

    @Override
    public List<ConversationMessage> getMessages(String conversationId) {
        List<ConversationMessage> messages = messageStore.get(conversationId);
        return messages != null ? Collections.unmodifiableList(messages) : List.of();
    }

    @Override
    public List<SuspendedConversation> getActiveConversations() {
        return conversationManager.getActiveConversations();
    }

    @Override
    public int cleanupExpired() {
        return conversationManager.cleanupExpired();
    }

    /**
     * Builds a human-readable summary of the submitted form data.
     */
    private String buildFormDataSummary(Map<String, Object> formData) {
        if (formData == null || formData.isEmpty()) {
            return "(empty)";
        }
        StringBuilder sb = new StringBuilder();
        formData.forEach((key, value) -> {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(key).append("=").append(value);
        });
        return sb.toString();
    }
}
