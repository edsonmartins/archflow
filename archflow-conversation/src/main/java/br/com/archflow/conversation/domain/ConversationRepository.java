package br.com.archflow.conversation.domain;

import java.util.List;
import java.util.Optional;

/**
 * Repository contract for {@link Conversation} and {@link Message} persistence.
 *
 * <p>All operations are tenant-scoped to enforce isolation. Implementations
 * must validate that the tenantId matches before returning data.
 */
public interface ConversationRepository {

    // ── Conversation ────────────────────────────────────────────────

    /**
     * Save (insert or update) a conversation.
     */
    Conversation save(Conversation conversation);

    /**
     * Find conversation by id within a tenant.
     */
    Optional<Conversation> findById(String tenantId, String conversationId);

    /**
     * List all conversations for a tenant.
     */
    List<Conversation> listByTenant(String tenantId);

    /**
     * List all conversations for a specific user within a tenant.
     */
    List<Conversation> listByUser(String tenantId, String userId);

    /**
     * Delete a conversation and all its messages.
     */
    boolean delete(String tenantId, String conversationId);

    // ── Message ─────────────────────────────────────────────────────

    /**
     * Append a message to a conversation.
     */
    Message addMessage(Message message);

    /**
     * List all messages of a conversation in chronological order.
     */
    List<Message> listMessages(String tenantId, String conversationId);

    /**
     * List the last N messages of a conversation (for chat memory window).
     */
    List<Message> listRecentMessages(String tenantId, String conversationId, int limit);

    /**
     * Total count of messages in a conversation.
     */
    int countMessages(String tenantId, String conversationId);
}
