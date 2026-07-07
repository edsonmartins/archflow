package br.com.archflow.conversation.state;

import java.util.List;
import java.util.Optional;

/**
 * Armazenamento das conversas suspensas (estado de suspend/resume). Abstrai os
 * mapas em memória do {@link br.com.archflow.conversation.ConversationManager},
 * permitindo uma implementação durável (JDBC) para que conversas aguardando
 * input humano sobrevivam a restart.
 *
 * <p>Cada conversa é indexada por {@code conversationId} (chave) e por
 * {@code resumeToken} (para retomada). As implementações devem manter os dois
 * índices consistentes.
 */
public interface SuspendedConversationStore {

    /** Cria ou atualiza a conversa suspensa (upsert por {@code conversationId}). */
    void save(SuspendedConversation conversation);

    /** Busca pelo token de retomada. */
    Optional<SuspendedConversation> findByToken(String resumeToken);

    /** Busca pelo id da conversa. */
    Optional<SuspendedConversation> findById(String conversationId);

    /**
     * Remove a conversa (e seu índice por token).
     *
     * @return {@code true} se existia
     */
    boolean deleteById(String conversationId);

    /** Todas as conversas suspensas conhecidas (para cleanup/estatísticas). */
    List<SuspendedConversation> findAll();
}
