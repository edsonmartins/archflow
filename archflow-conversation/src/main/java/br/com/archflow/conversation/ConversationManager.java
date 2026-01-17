package br.com.archflow.conversation;

import br.com.archflow.conversation.event.ArchflowEvent;
import br.com.archflow.conversation.form.FormData;
import br.com.archflow.conversation.state.SuspendedConversation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Manages conversation lifecycle with suspend/resume capability.
 *
 * <p>This service handles:
 * <ul>
 *   <li>Suspending conversations when user input is needed</li>
 *   <li>Generating secure resume tokens</li>
 *   <li>Storing and retrieving suspended conversations</li>
 *   <li>Resuming conversations with user-provided data</li>
 *   <li>Cleanup of expired conversations</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * ConversationManager manager = ConversationManager.getInstance();
 *
 * // Suspend conversation
 * SuspendedConversation suspended = manager.suspend(
 *     "conv-123",
 *     "workflow-456",
 *     FormData.Templates.userRegistration()
 * );
 *
 * // Later, resume with user data
 * Optional<SuspendedConversation> resumed = manager.resume(
 *     suspended.getResumeToken(),
 *     Map.of("name", "John", "email", "john@example.com")
 * );
 * }</pre>
 *
 * @see SuspendedConversation
 * @see FormData
 */
public class ConversationManager {

    private static final Logger log = LoggerFactory.getLogger(ConversationManager.class);
    private static final int DEFAULT_TIMEOUT_MINUTES = 30;
    private static final int DEFAULT_TOKEN_LENGTH = 32;

    private static volatile ConversationManager instance;

    private final Map<String, SuspendedConversation> conversationsByToken;
    private final Map<String, SuspendedConversation> conversationsById;
    private final Map<String, Consumer<ArchflowEvent>> eventSubscribers;
    private final Duration defaultTimeout;

    private ConversationManager(Duration defaultTimeout) {
        this.conversationsByToken = new ConcurrentHashMap<>();
        this.conversationsById = new ConcurrentHashMap<>();
        this.eventSubscribers = new ConcurrentHashMap<>();
        this.defaultTimeout = defaultTimeout != null ? defaultTimeout : Duration.ofMinutes(DEFAULT_TIMEOUT_MINUTES);
    }

    /**
     * Gets the singleton instance with default timeout.
     */
    public static ConversationManager getInstance() {
        return getInstance(Duration.ofMinutes(DEFAULT_TIMEOUT_MINUTES));
    }

    /**
     * Gets the singleton instance with custom timeout.
     */
    public static synchronized ConversationManager getInstance(Duration timeout) {
        if (instance == null) {
            instance = new ConversationManager(timeout);
        }
        return instance;
    }

    /**
     * Resets the singleton instance (mainly for testing).
     */
    public static synchronized void reset() {
        instance = null;
    }

    /**
     * Suspends a conversation and returns the suspended state.
     *
     * @param conversationId The unique conversation identifier
     * @param workflowId The workflow being executed
     * @param form The form to present to the user
     * @return The suspended conversation with resume token
     */
    public SuspendedConversation suspend(String conversationId, String workflowId, FormData form) {
        return suspend(conversationId, workflowId, null, form, defaultTimeout, null);
    }

    /**
     * Suspends a conversation with full options.
     */
    public SuspendedConversation suspend(
            String conversationId,
            String workflowId,
            String workflowExecutionId,
            FormData form,
            Duration timeout,
            Map<String, Object> context) {

        String resumeToken = generateResumeToken();
        Instant expiresAt = Instant.now().plus(timeout != null ? timeout : defaultTimeout);

        SuspendedConversation suspended = SuspendedConversation.builder()
                .conversationId(conversationId)
                .resumeToken(resumeToken)
                .workflowId(workflowId)
                .workflowExecutionId(workflowExecutionId)
                .form(form)
                .expiresAt(expiresAt)
                .context(context)
                .build();

        conversationsByToken.put(resumeToken, suspended);
        conversationsById.put(conversationId, suspended);

        log.info("Suspended conversation {} with token {} (expires at {})",
                conversationId, resumeToken, expiresAt);

        // Publish suspend event
        publishEvent(ArchflowEvent.suspend(conversationId, resumeToken, form));

        return suspended;
    }

    /**
     * Resumes a suspended conversation with the provided form data.
     *
     * @param resumeToken The resume token from the suspended conversation
     * @param formData The user-submitted form data
     * @return Optional containing the resumed conversation, or empty if token is invalid
     */
    public Optional<SuspendedConversation> resume(String resumeToken, Map<String, Object> formData) {
        SuspendedConversation suspended = conversationsByToken.get(resumeToken);

        if (suspended == null) {
            log.warn("Invalid resume token: {}", resumeToken);
            return Optional.empty();
        }

        if (suspended.isExpired()) {
            log.warn("Attempted to resume expired conversation {}: expired at {}",
                    suspended.getConversationId(), suspended.getExpiresAt());
            conversationsByToken.remove(resumeToken);
            conversationsById.remove(suspended.getConversationId());
            return Optional.empty();
        }

        SuspendedConversation resumed = suspended.resume(formData);
        conversationsByToken.put(resumeToken, resumed);
        conversationsById.put(suspended.getConversationId(), resumed);

        log.info("Resumed conversation {} with token {}", suspended.getConversationId(), resumeToken);

        // Publish resume event
        publishEvent(ArchflowEvent.resume(suspended.getConversationId(), formData));

        return Optional.of(resumed);
    }

    /**
     * Gets a suspended conversation by its resume token.
     */
    public Optional<SuspendedConversation> getByToken(String resumeToken) {
        SuspendedConversation suspended = conversationsByToken.get(resumeToken);
        if (suspended != null && suspended.isExpired()) {
            conversationsByToken.remove(resumeToken);
            conversationsById.remove(suspended.getConversationId());
            return Optional.empty();
        }
        return Optional.ofNullable(suspended);
    }

    /**
     * Gets a suspended conversation by its conversation ID.
     */
    public Optional<SuspendedConversation> getById(String conversationId) {
        SuspendedConversation suspended = conversationsById.get(conversationId);
        if (suspended != null && suspended.isExpired()) {
            conversationsByToken.remove(suspended.getResumeToken());
            conversationsById.remove(conversationId);
            return Optional.empty();
        }
        return Optional.ofNullable(suspended);
    }

    /**
     * Cancels a suspended conversation.
     */
    public boolean cancel(String conversationId) {
        SuspendedConversation suspended = conversationsById.get(conversationId);
        if (suspended == null) {
            return false;
        }

        SuspendedConversation cancelled = suspended.cancel();
        conversationsByToken.remove(suspended.getResumeToken());
        conversationsById.remove(conversationId);

        log.info("Cancelled conversation {}", conversationId);
        return true;
    }

    /**
     * Removes a completed conversation from the registry.
     */
    public boolean complete(String conversationId) {
        SuspendedConversation suspended = conversationsById.remove(conversationId);
        if (suspended != null) {
            conversationsByToken.remove(suspended.getResumeToken());
            log.info("Completed conversation {}", conversationId);
            return true;
        }
        return false;
    }

    /**
     * Cleans up expired conversations.
     *
     * @return The number of conversations cleaned up
     */
    public int cleanupExpired() {
        int cleaned = 0;

        Iterator<Map.Entry<String, SuspendedConversation>> it =
                conversationsById.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<String, SuspendedConversation> entry = it.next();
            SuspendedConversation suspended = entry.getValue();

            if (suspended.isExpired()) {
                it.remove();
                conversationsByToken.remove(suspended.getResumeToken());
                cleaned++;

                log.info("Cleaned up expired conversation {}", entry.getKey());
            }
        }

        return cleaned;
    }

    /**
     * Gets the number of active (waiting) conversations.
     */
    public int getActiveCount() {
        return (int) conversationsById.values().stream()
                .filter(SuspendedConversation::isActive)
                .count();
    }

    /**
     * Gets all active conversations.
     */
    public List<SuspendedConversation> getActiveConversations() {
        return conversationsById.values().stream()
                .filter(SuspendedConversation::isActive)
                .toList();
    }

    /**
     * Subscribes to conversation events.
     */
    public void subscribe(String subscriberId, Consumer<ArchflowEvent> handler) {
        eventSubscribers.put(subscriberId, handler);
    }

    /**
     * Unsubscribes from conversation events.
     */
    public void unsubscribe(String subscriberId) {
        eventSubscribers.remove(subscriberId);
    }

    /**
     * Generates a secure resume token.
     */
    private String generateResumeToken() {
        return UUID.randomUUID().toString().replace("-", "") +
                "-" +
                UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * Publishes an event to all subscribers.
     */
    private void publishEvent(ArchflowEvent event) {
        eventSubscribers.values().forEach(subscriber -> {
            try {
                subscriber.accept(event);
            } catch (Exception e) {
                log.error("Error publishing event to subscriber", e);
            }
        });
    }

    /**
     * Creates a timeout task that can be scheduled for periodic cleanup.
     */
    public Runnable createCleanupTask() {
        return this::cleanupExpired;
    }

    /**
     * Gets statistics about suspended conversations.
     */
    public ConversationStats getStats() {
        Map<SuspendedConversation.ConversationStatus, Long> counts =
                conversationsById.values().stream()
                        .collect(java.util.stream.Collectors.groupingByConcurrent(
                                SuspendedConversation::getStatus,
                                java.util.stream.Collectors.counting()
                        ));

        return new ConversationStats(
                conversationsById.size(),
                getActiveCount(),
                counts.getOrDefault(SuspendedConversation.ConversationStatus.WAITING, 0L).intValue(),
                counts.getOrDefault(SuspendedConversation.ConversationStatus.RESUMED, 0L).intValue(),
                counts.getOrDefault(SuspendedConversation.ConversationStatus.CANCELLED, 0L).intValue(),
                counts.getOrDefault(SuspendedConversation.ConversationStatus.TIMED_OUT, 0L).intValue()
        );
    }

    /**
     * Statistics about suspended conversations.
     */
    public record ConversationStats(
            int total,
            int active,
            int waiting,
            int resumed,
            int cancelled,
            int timedOut
    ) {}
}
