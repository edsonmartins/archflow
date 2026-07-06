package br.com.archflow.conversation.state;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementação em memória do {@link SuspendedConversationStore} — preserva o
 * comportamento histórico do {@code ConversationManager} (dois mapas por token e
 * por id). Adequada a dev/teste; perde o estado no restart.
 */
public class InMemorySuspendedConversationStore implements SuspendedConversationStore {

    private final ConcurrentHashMap<String, SuspendedConversation> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SuspendedConversation> byToken = new ConcurrentHashMap<>();

    @Override
    public void save(SuspendedConversation conversation) {
        // Se o id já existia com outro token (não deveria — o token é estável),
        // remove o índice antigo antes de reindexar, para não vazar entradas.
        SuspendedConversation previous = byId.put(conversation.getConversationId(), conversation);
        if (previous != null && !previous.getResumeToken().equals(conversation.getResumeToken())) {
            byToken.remove(previous.getResumeToken());
        }
        byToken.put(conversation.getResumeToken(), conversation);
    }

    @Override
    public Optional<SuspendedConversation> findByToken(String resumeToken) {
        return Optional.ofNullable(byToken.get(resumeToken));
    }

    @Override
    public Optional<SuspendedConversation> findById(String conversationId) {
        return Optional.ofNullable(byId.get(conversationId));
    }

    @Override
    public boolean deleteById(String conversationId) {
        SuspendedConversation removed = byId.remove(conversationId);
        if (removed != null) {
            byToken.remove(removed.getResumeToken());
            return true;
        }
        return false;
    }

    @Override
    public List<SuspendedConversation> findAll() {
        return List.copyOf(byId.values());
    }
}
