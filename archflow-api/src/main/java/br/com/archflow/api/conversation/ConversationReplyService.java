package br.com.archflow.api.conversation;

import br.com.archflow.agent.streaming.ArchflowDomain;
import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.ArchflowEventType;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.conversation.message.ConversationMessage;
import br.com.archflow.conversation.service.ConversationService;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.langchain4j.provider.LLMResolutionRequest;
import br.com.archflow.model.config.ResolvedLLMConfig;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Gera a resposta do agente para uma mensagem de conversa e a publica via SSE.
 *
 * <p>Fluxo: mensagem do usuário já persistida → {@link #replyAsync} → monta o
 * prompt com o histórico recente → {@code ChatModel.chat} (config resolvida
 * pelo {@link LLMConfigResolver}, mesmo caminho do AssistService) → persiste a
 * resposta → publica eventos {@code chat/start|message|end} (ou {@code error})
 * no canal {@code tenantId:conversationId} do {@link EventStreamRegistry},
 * consumido por {@code GET /api/stream/...}.
 *
 * <p>Antes deste serviço, o POST de mensagem apenas persistia — o frontend
 * ficava com spinner eterno esperando uma resposta que nunca chegava
 * (bloqueador da auditoria de homologação; decisão 0.4: implementar).
 */
@Service
public class ConversationReplyService {

    private static final Logger log = LoggerFactory.getLogger(ConversationReplyService.class);
    private static final int HISTORY_LIMIT = 20;

    /**
     * O frontend conecta o stream com o tenant do store ou {@code "default"}
     * como fallback — publicamos nos dois canais quando diferem.
     */
    private static final String FALLBACK_TENANT = "default";

    private final ConversationService conversationService;
    private final EventStreamRegistry registry;
    private final LLMConfigResolver llmConfigResolver;
    private final ResolvedLLMConfig platformDefault;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ConversationReplyService(ConversationService conversationService,
                                    EventStreamRegistry registry,
                                    LLMConfigResolver llmConfigResolver,
                                    ResolvedLLMConfig platformDefaultLLMConfig) {
        this.conversationService = conversationService;
        this.registry = registry;
        this.llmConfigResolver = llmConfigResolver;
        this.platformDefault = platformDefaultLLMConfig;
    }

    /** Dispara a geração da resposta sem bloquear o request do POST. */
    public void replyAsync(String conversationId, String tenantId) {
        String tenant = tenantId == null || tenantId.isBlank() ? FALLBACK_TENANT : tenantId;
        executor.submit(() -> reply(conversationId, tenant));
    }

    void reply(String conversationId, String tenantId) {
        try {
            broadcast(tenantId, conversationId, ArchflowEventType.START,
                    Map.of("conversationId", conversationId));

            List<ConversationMessage> history = conversationService.getMessages(conversationId);
            String prompt = buildPrompt(history);

            ChatModel model = llmConfigResolver.resolveModel(
                    LLMResolutionRequest.builder(platformDefault).build());
            String replyText = model.chat(prompt);

            ConversationMessage assistant = ConversationMessage.assistant(conversationId, replyText);
            conversationService.addMessage(assistant);

            broadcast(tenantId, conversationId, ArchflowEventType.MESSAGE, Map.of(
                    "conversationId", conversationId,
                    "messageId", assistant.id(),
                    "role", "assistant",
                    "content", replyText));
            broadcast(tenantId, conversationId, ArchflowEventType.END,
                    Map.of("conversationId", conversationId));

        } catch (Exception e) {
            log.warn("Falha ao gerar resposta para conversa {}: {}", conversationId, e.toString());
            broadcast(tenantId, conversationId, ArchflowEventType.ERROR, Map.of(
                    "conversationId", conversationId,
                    "message", "Falha ao gerar resposta: " + e.getMessage()));
        }
    }

    private void broadcast(String tenantId, String conversationId,
                           ArchflowEventType type, Map<String, Object> data) {
        ArchflowEvent event = ArchflowEvent.builder()
                .domain(ArchflowDomain.CHAT)
                .type(type)
                .tenantId(tenantId)
                .correlationId(conversationId)
                .executionId(tenantId + ":" + conversationId)
                .data(data)
                .build();
        registry.broadcast(tenantId + ":" + conversationId, event);
        if (!FALLBACK_TENANT.equals(tenantId)) {
            registry.broadcast(FALLBACK_TENANT + ":" + conversationId, event);
        }
    }

    /**
     * Prompt simples com o histórico recente rotulado por papel. Tool-calling e
     * memória avançada (guardrails/summarizer do archflow-conversation) são
     * evolução posterior — o contrato aqui é: mensagem entra, resposta sai.
     */
    private String buildPrompt(List<ConversationMessage> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("Você é o assistente do archflow. Responda de forma útil e concisa, ")
          .append("no idioma da última mensagem do usuário.\n\n");
        int start = Math.max(0, history.size() - HISTORY_LIMIT);
        for (int i = start; i < history.size(); i++) {
            ConversationMessage m = history.get(i);
            String role = switch (m.role()) {
                case USER -> "Usuário";
                case ASSISTANT -> "Assistente";
                case SYSTEM -> "Sistema";
                case TOOL -> "Ferramenta";
            };
            sb.append(role).append(": ").append(m.content()).append('\n');
        }
        sb.append("Assistente:");
        return sb.toString();
    }
}
