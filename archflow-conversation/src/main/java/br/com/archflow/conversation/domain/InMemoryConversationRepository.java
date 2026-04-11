package br.com.archflow.conversation.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory {@link ConversationRepository} for tests and dev environments.
 *
 * <p>Storage is partitioned by tenantId to ensure isolation:
 * <pre>
 *   conversations: tenantId → conversationId → Conversation
 *   messages:      tenantId → conversationId → List&lt;Message&gt;
 * </pre>
 */
public class InMemoryConversationRepository implements ConversationRepository {

    private final Map<String, Map<String, Conversation>> conversations = new ConcurrentHashMap<>();
    private final Map<String, Map<String, List<Message>>> messages = new ConcurrentHashMap<>();

    @Override
    public Conversation save(Conversation conversation) {
        Map<String, Conversation> tenantMap = conversations.computeIfAbsent(
                conversation.tenantId(), k -> new ConcurrentHashMap<>());
        tenantMap.put(conversation.id(), conversation);
        return conversation;
    }

    @Override
    public Optional<Conversation> findById(String tenantId, String conversationId) {
        Map<String, Conversation> tenantMap = conversations.get(tenantId);
        if (tenantMap == null) return Optional.empty();
        return Optional.ofNullable(tenantMap.get(conversationId));
    }

    @Override
    public List<Conversation> listByTenant(String tenantId) {
        Map<String, Conversation> tenantMap = conversations.get(tenantId);
        if (tenantMap == null) return List.of();
        return tenantMap.values().stream()
                .sorted(Comparator.comparing(Conversation::createdAt).reversed())
                .toList();
    }

    @Override
    public List<Conversation> listByUser(String tenantId, String userId) {
        return listByTenant(tenantId).stream()
                .filter(c -> userId.equals(c.userId()))
                .toList();
    }

    @Override
    public boolean delete(String tenantId, String conversationId) {
        Map<String, Conversation> tenantMap = conversations.get(tenantId);
        if (tenantMap == null) return false;
        Conversation removed = tenantMap.remove(conversationId);
        Map<String, List<Message>> tenantMessages = messages.get(tenantId);
        if (tenantMessages != null) tenantMessages.remove(conversationId);
        return removed != null;
    }

    @Override
    public Message addMessage(Message message) {
        Map<String, List<Message>> tenantMessages = messages.computeIfAbsent(
                message.tenantId(), k -> new ConcurrentHashMap<>());
        List<Message> conversationMessages = tenantMessages.computeIfAbsent(
                message.conversationId(), k -> new CopyOnWriteArrayList<>());
        conversationMessages.add(message);
        return message;
    }

    @Override
    public List<Message> listMessages(String tenantId, String conversationId) {
        Map<String, List<Message>> tenantMessages = messages.get(tenantId);
        if (tenantMessages == null) return List.of();
        List<Message> list = tenantMessages.get(conversationId);
        if (list == null) return List.of();
        return new ArrayList<>(list);
    }

    @Override
    public List<Message> listRecentMessages(String tenantId, String conversationId, int limit) {
        List<Message> all = listMessages(tenantId, conversationId);
        if (all.size() <= limit) return all;
        return new ArrayList<>(all.subList(all.size() - limit, all.size()));
    }

    @Override
    public int countMessages(String tenantId, String conversationId) {
        Map<String, List<Message>> tenantMessages = messages.get(tenantId);
        if (tenantMessages == null) return 0;
        List<Message> list = tenantMessages.get(conversationId);
        return list == null ? 0 : list.size();
    }
}
