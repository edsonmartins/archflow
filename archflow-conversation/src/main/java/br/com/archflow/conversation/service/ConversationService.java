package br.com.archflow.conversation.service;

import br.com.archflow.conversation.domain.Conversation;
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

    /**
     * O registro durável da conversa (dono, status, criação), em qualquer tenant — quem responde a uma
     * requisição confere o tenant dele. Vazio para uma conversa sem nenhuma mensagem.
     */
    Optional<Conversation> getRecord(String conversationId);

    /**
     * Cancela a conversa — suspensa ou ativa — e a marca como cancelada no registro.
     *
     * @return {@code false} se a conversa não existe
     */
    boolean cancel(String conversationId);

    /**
     * Retira o formulário que a conversa espera — o que o envio continuaria acabou sem ele (aprovação
     * recusada ou expirada, execução parada). Quem retira avisa a conversa.
     *
     * @return o estado retirado, ou vazio se a conversa não esperava um formulário
     */
    Optional<SuspendedConversation> withdrawForm(String conversationId);

    void addMessage(ConversationMessage message);

    List<ConversationMessage> getMessages(String conversationId);

    /**
     * Ids das conversas do tenant, mais as suspensas de qualquer tenant (a de uma suspensão sem mensagem
     * ainda não tem registro) — quem lista confere o dono de cada uma.
     */
    List<String> listConversationIds(String tenantId);

    List<SuspendedConversation> getActiveConversations();

    int cleanupExpired();
}
