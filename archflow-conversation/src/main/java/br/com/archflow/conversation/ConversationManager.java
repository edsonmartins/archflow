package br.com.archflow.conversation;

import br.com.archflow.conversation.event.ArchflowEvent;
import br.com.archflow.conversation.form.FormData;
import br.com.archflow.conversation.state.InMemorySuspendedConversationStore;
import br.com.archflow.conversation.state.SuspendedConversation;
import br.com.archflow.conversation.state.SuspendedConversationStore;
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

    private final SuspendedConversationStore store;
    private final Map<String, Consumer<ArchflowEvent>> eventSubscribers;
    private final Duration defaultTimeout;

    /**
     * Cria um manager sobre um {@link SuspendedConversationStore} injetado — passe
     * um store JDBC para tornar suspend/resume durável (sobrevive a restart).
     * Preferível ao {@link #getInstance()} em produção (DI). Um {@code store} nulo
     * cai para um store em memória.
     */
    public ConversationManager(Duration defaultTimeout, SuspendedConversationStore store) {
        this.store = store != null ? store : new InMemorySuspendedConversationStore();
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
            instance = new ConversationManager(timeout, new InMemorySuspendedConversationStore());
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

        store.save(suspended);

        log.info("Suspended conversation {} with token {} (expires at {})",
                conversationId, redactToken(resumeToken), expiresAt);

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
        SuspendedConversation suspended = store.findByToken(resumeToken).orElse(null);

        if (suspended == null) {
            registerFailedResume();
            log.warn("Invalid resume token: {}", redactToken(resumeToken));
            return Optional.empty();
        }

        if (suspended.isExpired()) {
            log.warn("Attempted to resume expired conversation {}: expired at {}",
                    suspended.getConversationId(), suspended.getExpiresAt());
            store.deleteById(suspended.getConversationId());
            return Optional.empty();
        }

        // formData nulo → mapa vazio: SuspendedConversation.resume usa Map.of(),
        // que rejeita valores nulos.
        SuspendedConversation resumed = suspended.resume(formData != null ? formData : Map.of());
        store.save(resumed);

        log.info("Resumed conversation {} with token {}",
                suspended.getConversationId(), redactToken(resumeToken));

        // Publish resume event
        publishEvent(ArchflowEvent.resume(suspended.getConversationId(), formData));

        return Optional.of(resumed);
    }

    /**
     * Gets a suspended conversation by its resume token.
     */
    public Optional<SuspendedConversation> getByToken(String resumeToken) {
        SuspendedConversation suspended = store.findByToken(resumeToken).orElse(null);
        if (suspended != null && suspended.isExpired()) {
            store.deleteById(suspended.getConversationId());
            return Optional.empty();
        }
        return Optional.ofNullable(suspended);
    }

    /**
     * Gets a suspended conversation by its conversation ID.
     */
    public Optional<SuspendedConversation> getById(String conversationId) {
        SuspendedConversation suspended = store.findById(conversationId).orElse(null);
        if (suspended != null && suspended.isExpired()) {
            store.deleteById(conversationId);
            return Optional.empty();
        }
        return Optional.ofNullable(suspended);
    }

    /**
     * Cancels a suspended conversation.
     */
    public boolean cancel(String conversationId) {
        boolean removed = store.deleteById(conversationId);
        if (removed) {
            log.info("Cancelled conversation {}", conversationId);
        }
        return removed;
    }

    /**
     * Removes a completed conversation from the registry.
     */
    public boolean complete(String conversationId) {
        boolean removed = store.deleteById(conversationId);
        if (removed) {
            log.info("Completed conversation {}", conversationId);
        }
        return removed;
    }

    /**
     * Cleans up expired conversations.
     *
     * @return The number of conversations cleaned up
     */
    public int cleanupExpired() {
        int cleaned = 0;
        for (SuspendedConversation suspended : store.findAll()) {
            if (suspended.isExpired()) {
                store.deleteById(suspended.getConversationId());
                cleaned++;
                log.info("Cleaned up expired conversation {}", suspended.getConversationId());
            }
        }
        return cleaned;
    }

    /**
     * Gets the number of active (waiting) conversations.
     */
    public int getActiveCount() {
        return (int) store.findAll().stream()
                .filter(SuspendedConversation::isActive)
                .count();
    }

    /**
     * Gets all active conversations.
     */
    public List<SuspendedConversation> getActiveConversations() {
        return store.findAll().stream()
                .filter(SuspendedConversation::isActive)
                .toList();
    }

    /**
     * Subscribes to conversation events (all tenants — backward compat).
     */
    public void subscribe(String subscriberId, Consumer<ArchflowEvent> handler) {
        subscribe("SYSTEM", subscriberId, handler);
    }

    /**
     * Subscribes to conversation events for a specific tenant.
     */
    public void subscribe(String tenantId, String subscriberId, Consumer<ArchflowEvent> handler) {
        String key = tenantSubscriberKey(tenantId, subscriberId);
        eventSubscribers.put(key, handler);
    }

    /**
     * Unsubscribes from conversation events.
     */
    public void unsubscribe(String subscriberId) {
        unsubscribe("SYSTEM", subscriberId);
    }

    /**
     * Unsubscribes from conversation events for a specific tenant.
     */
    public void unsubscribe(String tenantId, String subscriberId) {
        eventSubscribers.remove(tenantSubscriberKey(tenantId, subscriberId));
    }

    private String tenantSubscriberKey(String tenantId, String subscriberId) {
        return (tenantId != null ? tenantId : "SYSTEM") + ":" + subscriberId;
    }

    /**
     * Generates a secure resume token: 256 bits from {@link java.security.SecureRandom},
     * URL-safe Base64. The token space makes online guessing infeasible, which is
     * why invalid attempts are detected and logged (see {@link #registerFailedResume})
     * instead of triggering a global lock-out — a lock-out keyed on nothing but
     * the attempt itself would hand attackers a denial-of-service lever.
     */
    private String generateResumeToken() {
        byte[] bytes = new byte[DEFAULT_TOKEN_LENGTH];
        TOKEN_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static final java.security.SecureRandom TOKEN_RANDOM = new java.security.SecureRandom();

    /** Failed resume attempts inside the current detection window. */
    private final java.util.concurrent.atomic.AtomicInteger failedResumeAttempts =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicLong failedResumeWindowStart =
            new java.util.concurrent.atomic.AtomicLong();
    private static final int FAILED_RESUME_ALERT_THRESHOLD = 20;
    private static final long FAILED_RESUME_WINDOW_MILLIS = 60_000;

    /**
     * Records an invalid resume attempt and emits an alert when the rate inside
     * the detection window suggests token brute-forcing.
     */
    private void registerFailedResume() {
        long now = System.currentTimeMillis();
        long windowStart = failedResumeWindowStart.get();
        if (now - windowStart > FAILED_RESUME_WINDOW_MILLIS
                && failedResumeWindowStart.compareAndSet(windowStart, now)) {
            failedResumeAttempts.set(0);
        }
        int failures = failedResumeAttempts.incrementAndGet();
        if (failures == FAILED_RESUME_ALERT_THRESHOLD) {
            log.error("Possible resume-token brute-force: {} invalid attempts in the last {} ms",
                    failures, FAILED_RESUME_WINDOW_MILLIS);
        }
    }

    /** Renders a token safe for logs: first 8 chars only. */
    private static String redactToken(String token) {
        if (token == null) {
            return "null";
        }
        return token.length() <= 8 ? "***" : token.substring(0, 8) + "…";
    }

    /**
     * Publishes an event to subscribers matching the event's tenant.
     * If tenantId is null, publishes to all subscribers (backward compat).
     */
    private void publishEvent(ArchflowEvent event) {
        String eventTenantId = event.getEnvelope().tenantId();
        eventSubscribers.forEach((key, subscriber) -> {
            // If event has no tenant, send to all; otherwise filter by tenant prefix
            if (eventTenantId == null || key.startsWith(eventTenantId + ":") || key.startsWith("SYSTEM:")) {
                try {
                    subscriber.accept(event);
                } catch (Exception e) {
                    log.error("Error publishing event to subscriber {}", key, e);
                }
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
        List<SuspendedConversation> all = store.findAll();
        Map<SuspendedConversation.ConversationStatus, Long> counts =
                all.stream()
                        .collect(java.util.stream.Collectors.groupingBy(
                                SuspendedConversation::getStatus,
                                java.util.stream.Collectors.counting()
                        ));

        return new ConversationStats(
                all.size(),
                (int) all.stream().filter(SuspendedConversation::isActive).count(),
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
