package br.com.archflow.api.conversation;

import br.com.archflow.agent.streaming.ArchflowDomain;
import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.ArchflowEventType;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.api.config.TenantContext;
import br.com.archflow.conversation.message.ConversationMessage;
import br.com.archflow.conversation.service.ConversationService;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.langchain4j.provider.LLMProvider;
import br.com.archflow.langchain4j.provider.LLMResolutionRequest;
import br.com.archflow.model.config.ResolvedLLMConfig;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Gera a resposta do agente para uma mensagem de conversa e a publica via SSE.
 *
 * <p>Fluxo: mensagem do usuário já persistida → {@link #replyAsync} → monta o
 * prompt com o histórico recente → {@code StreamingChatModel.chat} (config resolvida
 * pelo {@link LLMConfigResolver}, mesmo caminho do AssistService) → um {@code chat/delta}
 * por parcial → persiste a resposta → {@code chat/message} com o texto completo e
 * {@code chat/end} (ou {@code error}) no canal {@code tenantId:conversationId} do
 * {@link EventStreamRegistry}, consumido por {@code GET /api/stream/...}.
 *
 * <p>Antes a resposta era o {@code ChatModel.chat} bloqueante: a bolha ficava vazia durante toda a
 * geração e o texto aparecia inteiro no fim, embora o chat já acumulasse deltas (RCHFLW-68). O
 * bloqueante continua só para o provider que declara não ter streaming
 * ({@link LLMProvider#supportsStreaming()}) — decidido pelo provider resolvido, antes de chamar, e
 * não por uma exceção do streaming: uma falha no meio da geração vira {@code error}, não uma
 * segunda chamada ao modelo.
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
     * Tenant de uma mensagem que chegou sem tenant (auth desligada, sem header): o mesmo que o
     * {@code GET /api/stream/{sessionId}} resolve para essa requisição ({@link TenantContext}), senão
     * a resposta sai num canal que ninguém assina. Antes era {@code "default"}, o tenant que a UI
     * inventava — e a resposta também ia para "{@code default:<conversa>}" quando ninguém ouvia no
     * canal do tenant, o que, com o stream aberto a qualquer tenant, entregava a resposta a quem
     * adivinhasse o id da conversa (RCHFLW-49).
     */
    private static final String FALLBACK_TENANT = TenantContext.DEFAULT_TENANT;

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

            // O tenant da conversa (atributo da requisição, ancorado no JWT) entra na resolução: escolhe
            // a chave dele (antes da da plataforma) e aplica os modelos permitidos (RCHFLW-79). Sem ele o
            // resolver tratava toda resposta como "nenhum tenant" e a restrição não valia aqui. O
            // FALLBACK_TENANT é só o canal de broadcast, não um tenant de verdade.
            LLMResolutionRequest request = LLMResolutionRequest.builder(platformDefault)
                    .tenantId(FALLBACK_TENANT.equals(tenantId) ? null : tenantId)
                    .build();
            String replyText = streams(request)
                    ? streamReply(request, prompt, tenantId, conversationId)
                    : llmConfigResolver.resolveModel(request).chat(prompt);

            publishAssistantMessage(conversationId, tenantId, replyText);

        } catch (Exception e) {
            Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
            log.warn("Falha ao gerar resposta para conversa {}: {}", conversationId, cause.toString());
            broadcast(tenantId, conversationId, ArchflowEventType.ERROR, Map.of(
                    "conversationId", conversationId,
                    "message", "Falha ao gerar resposta: " + cause.getMessage()));
        }
    }

    /**
     * Se o provider resolvido para a requisição declara streaming. Um provider que o catálogo não
     * conhece segue o caminho bloqueante, que falha com a mensagem da resolução dele.
     */
    private boolean streams(LLMResolutionRequest request) {
        ResolvedLLMConfig resolved = llmConfigResolver.resolve(request);
        return LLMProvider.fromId(resolved.provider()).map(LLMProvider::supportsStreaming).orElse(false);
    }

    /**
     * Gera em streaming, publicando cada parcial como {@code chat/delta} assim que chega, e devolve o
     * texto completo. Espera o fim aqui — a thread já é a virtual do {@link #replyAsync} — para que
     * persistir e publicar o fim continue num ponto só, com o mesmo tratamento de erro do bloqueante.
     */
    private String streamReply(LLMResolutionRequest request, String prompt, String tenantId,
                               String conversationId) {
        StreamingChatModel model = llmConfigResolver.resolveStreamingModel(request);
        StringBuilder received = new StringBuilder();
        CompletableFuture<ChatResponse> done = new CompletableFuture<>();
        model.chat(prompt, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
                if (partial == null || partial.isEmpty()) {
                    return;
                }
                received.append(partial);
                broadcast(tenantId, conversationId, ArchflowEventType.DELTA, Map.of(
                        "conversationId", conversationId,
                        "content", partial));
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                done.complete(response);
            }

            @Override
            public void onError(Throwable error) {
                done.completeExceptionally(error);
            }
        });
        ChatResponse response = done.join();
        // O texto final do provider é o oficial; os deltas são o mesmo texto em partes, e só valem
        // quando a resposta final não traz texto (provider que só entrega pelas parciais).
        String text = response != null && response.aiMessage() != null ? response.aiMessage().text() : null;
        return text != null ? text : received.toString();
    }

    /**
     * Persiste uma fala do assistente e a publica como resposta completa: {@code chat/start},
     * {@code chat/message} e {@code chat/end}. É por aqui que a resposta de um fluxo retomado pelo
     * formulário chega ao chat (RCHFLW-67) — o mesmo canal e o mesmo formato da resposta do agente.
     */
    void publishReply(String conversationId, String tenantId, ConversationMessage message) {
        String tenant = channelTenant(tenantId);
        broadcast(tenant, conversationId, ArchflowEventType.START, Map.of("conversationId", conversationId));
        publishMessage(conversationId, tenant, message);
    }

    /**
     * Publica uma fala do usuário já gravada ({@code chat/message}, papel {@code user}, com o id gravado),
     * sem {@code chat/start}/{@code chat/end}: ela não é uma resposta. Sem isto, quem acompanhava a
     * conversa em outra aba via só as respostas do assistente (RCHFLW-194); quem enviou casa a fala pelo
     * {@code messageId} com a que já desenhou.
     */
    public void publishUserMessage(String conversationId, String tenantId, ConversationMessage message) {
        broadcast(channelTenant(tenantId), conversationId, ArchflowEventType.MESSAGE,
                messageData(conversationId, message));
    }

    /**
     * Publica uma fala do sistema já gravada por outro caminho — o "Conversa cancelada" que o
     * cancelamento grava — no formato de {@link #publishReply} ({@code chat/start}, {@code chat/message},
     * {@code chat/end}), sem gravá-la de novo. Sem isto o segundo visualizador recebia só o
     * {@code interaction/cancel}: o formulário sumia, mas a transcrição dele não dizia que a conversa
     * acabou. O {@code messageId} é o gravado, e é por ele que o chat de quem cancelou não a repete.
     */
    public void publishStoredNotice(String conversationId, String tenantId, ConversationMessage message) {
        String tenant = channelTenant(tenantId);
        broadcast(tenant, conversationId, ArchflowEventType.START, Map.of("conversationId", conversationId));
        broadcast(tenant, conversationId, ArchflowEventType.MESSAGE, messageData(conversationId, message));
        broadcast(tenant, conversationId, ArchflowEventType.END, Map.of("conversationId", conversationId));
    }

    /** Persiste e publica uma falha que encerra a espera do chat ({@code chat/error}). */
    void publishFailure(String conversationId, String tenantId, String message) {
        String tenant = channelTenant(tenantId);
        conversationService.addMessage(ConversationMessage.system(conversationId, message));
        broadcast(tenant, conversationId, ArchflowEventType.ERROR, Map.of(
                "conversationId", conversationId,
                "message", message));
    }

    private void publishAssistantMessage(String conversationId, String tenantId, String text) {
        publishMessage(conversationId, tenantId, ConversationMessage.assistant(conversationId, text));
    }

    private void publishMessage(String conversationId, String tenantId, ConversationMessage message) {
        conversationService.addMessage(message);
        broadcast(tenantId, conversationId, ArchflowEventType.MESSAGE, messageData(conversationId, message));
        broadcast(tenantId, conversationId, ArchflowEventType.END, Map.of("conversationId", conversationId));
    }

    private static Map<String, Object> messageData(String conversationId, ConversationMessage message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conversationId", conversationId);
        data.put("messageId", message.id());
        data.put("role", message.role().name().toLowerCase(java.util.Locale.ROOT));
        data.put("content", message.content());
        return data;
    }

    private static String channelTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? FALLBACK_TENANT : tenantId;
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
        // Só o canal do tenant de quem mandou a mensagem: o stream agora só abre no tenant da
        // própria requisição, então é ali — e só ali — que a UI escuta.
        registry.broadcast(tenantId + ":" + conversationId, event);
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
