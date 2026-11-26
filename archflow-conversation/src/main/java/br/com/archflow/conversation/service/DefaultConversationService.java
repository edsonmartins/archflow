package br.com.archflow.conversation.service;

import br.com.archflow.conversation.ConversationManager;
import br.com.archflow.conversation.domain.Conversation;
import br.com.archflow.conversation.domain.ConversationRepository;
import br.com.archflow.conversation.domain.InMemoryConversationRepository;
import br.com.archflow.conversation.domain.Message;
import br.com.archflow.conversation.form.FormData;
import br.com.archflow.conversation.message.ConversationMessage;
import br.com.archflow.conversation.state.SuspendedConversation;
import br.com.archflow.model.tenant.DefaultTenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Default implementation of {@link ConversationService}.
 *
 * <p>Delegates suspend/resume logic to {@link ConversationManager} and adds
 * message tracking with automatic system/user messages on lifecycle events.</p>
 *
 * <p><b>Transcrição durável (RCHFLW-181).</b> Registro e mensagens vão para o
 * {@link ConversationRepository} — o JDBC ({@code conversations} e {@code conversation_messages},
 * migration {@code V2_1}) sob {@code archflow.persistence.jdbc.enabled=true}, o em memória em dev. Antes
 * as mensagens viviam num mapa deste objeto: com JDBC ligado as tabelas ficavam vazias, o log anunciava
 * um histórico durável que não existia e toda conversa sumia no restart.</p>
 *
 * <p>O registro nasce com a primeira mensagem, no tenant dela ({@link #OWNER_METADATA}) — ou, sem a
 * marca, no do contexto da conversa suspensa, senão no tenant padrão. É o mesmo dono que o controller de
 * conversas já calculava pelas mensagens; agora ele fica numa coluna, e as mensagens seguintes, que não
 * trazem o tenant (a resposta do agente, "Formulário enviado", o cancelamento), são gravadas no tenant do
 * registro. O registro também guarda o status que sobrevive ao estado de suspensão: o cancelamento
 * ({@link Conversation.ConversationStatus#CANCELLED}).</p>
 */
public class DefaultConversationService implements ConversationService {

    private static final Logger log = LoggerFactory.getLogger(DefaultConversationService.class);

    /**
     * Chave do tenant dono nos metadados de uma mensagem e no contexto de uma suspensão — a mesma que o
     * controller de conversas grava na mensagem que abre a conversa.
     */
    public static final String OWNER_METADATA = "tenantId";

    /**
     * Usuário do registro quando a conversa não diz qual é. A coluna é obrigatória, e as conversas da UI
     * e da API nunca identificaram um usuário final (o {@code userId} delas sempre veio do contexto, nulo).
     */
    static final String UNKNOWN_USER = "";

    /** Textos de sistema em português, como as outras falas do servidor ("Conversa iniciada"). */
    static final String SUSPENDED_NOTICE = "Conversa suspensa: aguardando o preenchimento do formulário";
    static final String CANCELLED_NOTICE = "Conversa cancelada";
    static final String FORM_SUBMITTED_PREFIX = "Formulário enviado: ";

    private final ConversationManager conversationManager;
    private final ConversationRepository repository;

    /**
     * Creates a new DefaultConversationService, with the transcript kept in memory.
     *
     * @param conversationManager The conversation manager to delegate to
     */
    public DefaultConversationService(ConversationManager conversationManager) {
        this(conversationManager, new InMemoryConversationRepository());
    }

    /**
     * Cria o serviço com a transcrição no {@code repository} — passe o JDBC para que ela sobreviva a
     * restart.
     */
    public DefaultConversationService(ConversationManager conversationManager, ConversationRepository repository) {
        this.conversationManager = Objects.requireNonNull(conversationManager, "conversationManager");
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public SuspendedConversation suspend(String conversationId, String workflowId, FormData form) {
        SuspendedConversation suspended = conversationManager.suspend(conversationId, workflowId, form);

        addMessage(ConversationMessage.system(conversationId, SUSPENDED_NOTICE));

        log.debug("Suspended conversation {} via service layer", conversationId);
        return suspended;
    }

    @Override
    public SuspendedConversation suspend(String conversationId, String workflowId,
                                          String executionId, FormData form, Duration timeout,
                                          Map<String, Object> context) {
        SuspendedConversation suspended = conversationManager.suspend(
                conversationId, workflowId, executionId, form, timeout, context);

        addMessage(ConversationMessage.system(conversationId, SUSPENDED_NOTICE));

        log.debug("Suspended conversation {} with full options via service layer", conversationId);
        return suspended;
    }

    @Override
    public Optional<SuspendedConversation> resume(String resumeToken, Map<String, Object> formData) {
        Optional<SuspendedConversation> resumed = conversationManager.resume(resumeToken, formData);

        resumed.ifPresent(conversation -> {
            String summary = buildFormDataSummary(formData);
            addMessage(ConversationMessage.user(conversation.getConversationId(),
                    FORM_SUBMITTED_PREFIX + summary));

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
    public Optional<Conversation> getRecord(String conversationId) {
        return conversationId == null ? Optional.empty() : repository.findByIdAcrossTenants(conversationId);
    }

    /**
     * Cancela a conversa: retira a suspensão (se houver) e marca o registro como
     * {@link Conversation.ConversationStatus#CANCELLED}. Uma conversa ativa, nunca suspensa, também se
     * cancela — antes o {@code DELETE} dela respondia "não encontrada" enquanto o {@code GET} a devolvia.
     * Cancelar de novo não acrescenta nada.
     *
     * @return {@code false} só quando a conversa não existe
     */
    @Override
    public boolean cancel(String conversationId) {
        Optional<Conversation> record = getRecord(conversationId);
        if (record.isEmpty() && conversationManager.getById(conversationId).isEmpty()) {
            return false;
        }
        if (record.map(r -> r.status() == Conversation.ConversationStatus.CANCELLED).orElse(false)) {
            conversationManager.cancel(conversationId);
            return true;
        }
        // A mensagem antes de apagar a suspensão: numa conversa que só existia suspensa, é ela que cria o
        // registro, e o dono dele vem do contexto da suspensão.
        addMessage(ConversationMessage.system(conversationId, CANCELLED_NOTICE));
        conversationManager.cancel(conversationId);
        getRecord(conversationId).ifPresent(r ->
                repository.save(r.withStatus(Conversation.ConversationStatus.CANCELLED)));
        log.debug("Cancelled conversation {} via service layer", conversationId);
        return true;
    }

    /**
     * Retira o formulário. O registro vem antes de apagar a suspensão, pela mesma razão do
     * {@link #cancel}: numa conversa que só existia suspensa (gravada antes da transcrição durável), o
     * contexto da suspensão é o único lugar que diz o tenant dono — apagada ela, o aviso que quem retira
     * publica em seguida criaria o registro no tenant padrão, e o dono passaria a receber 404.
     */
    @Override
    public Optional<SuspendedConversation> withdrawForm(String conversationId) {
        conversationManager.peek(conversationId)
                .filter(s -> s.getStatus() == SuspendedConversation.ConversationStatus.WAITING)
                .ifPresent(s -> recordFor(conversationId,
                        () -> DefaultTenant.orDefault(tenantOfContext(s.getContext())), s.getCreatedAt()));
        return conversationManager.withdraw(conversationId);
    }

    @Override
    public void addMessage(ConversationMessage message) {
        Conversation record = recordFor(message.conversationId(), () -> ownerOf(message), message.timestamp());
        repository.addMessage(toStored(message, record.tenantId()));
    }

    @Override
    public List<ConversationMessage> getMessages(String conversationId) {
        return getRecord(conversationId)
                .map(record -> repository.listMessages(record.tenantId(), conversationId).stream()
                        .map(DefaultConversationService::toConversationMessage)
                        .toList())
                .orElse(List.of());
    }

    @Override
    public List<String> listConversationIds(String tenantId) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        repository.listByTenant(DefaultTenant.orDefault(tenantId)).forEach(record -> ids.add(record.id()));
        // As suspensas entram todas: quem lista confere o dono de cada uma (uma suspensão feita direto
        // pelo manager, sem mensagem nenhuma, ainda não tem registro).
        for (SuspendedConversation conversation : conversationManager.getActiveConversations()) {
            ids.add(conversation.getConversationId());
        }
        return List.copyOf(ids);
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
     * O registro da conversa, criado se ainda não existe — com o dono que {@code owner} calcula só nesse
     * caso. Criar é raro (a primeira fala) e corre contra outra primeira fala da mesma conversa: o
     * {@code synchronized} fecha a corrida neste processo, e entre réplicas a chave primária recusa a
     * segunda inserção — relida, ela encontra o registro da primeira.
     */
    private Conversation recordFor(String conversationId, Supplier<String> owner, Instant createdAt) {
        Optional<Conversation> existing = getRecord(conversationId);
        if (existing.isPresent()) {
            return existing.get();
        }
        synchronized (this) {
            existing = getRecord(conversationId);
            if (existing.isPresent()) {
                return existing.get();
            }
            Conversation created = new Conversation(conversationId, owner.get(), UNKNOWN_USER, null,
                    Conversation.ConversationStatus.ACTIVE, null, null, createdAt, createdAt);
            try {
                return repository.save(created);
            } catch (RuntimeException e) {
                return getRecord(conversationId).orElseThrow(() -> e);
            }
        }
    }

    /**
     * O tenant da mensagem que abre a conversa; sem a marca, o da conversa suspensa; senão o padrão.
     *
     * <p>A suspensa é lida com {@link ConversationManager#peek}, não com {@code getById}: este consulta
     * apaga a vencida, e ler o dono não pode mudar o que a varredura de vencidas encontra. Vencida ou não,
     * o tenant de quem suspendeu continua sendo o dono.</p>
     */
    private String ownerOf(ConversationMessage message) {
        if (message.metadata() != null && message.metadata().get(OWNER_METADATA) instanceof String tenant
                && !tenant.isBlank()) {
            return tenant;
        }
        return DefaultTenant.orDefault(conversationManager.peek(message.conversationId())
                .map(SuspendedConversation::getContext)
                .map(DefaultConversationService::tenantOfContext)
                .orElse(null));
    }

    /**
     * Tenant gravado no contexto da suspensão. A retomada move o contexto inteiro para
     * {@code originalContext}, então desce até ele.
     */
    private static String tenantOfContext(Map<String, Object> context) {
        Map<?, ?> current = context;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current.get(OWNER_METADATA) instanceof String s && !s.isBlank()) {
                return s;
            }
            current = current.get("originalContext") instanceof Map<?, ?> original ? original : null;
        }
        return null;
    }

    private static Message toStored(ConversationMessage message, String tenantId) {
        Message.SenderType sender = switch (message.role()) {
            case USER -> Message.SenderType.USER;
            case ASSISTANT, TOOL -> Message.SenderType.AGENT;
            case SYSTEM -> Message.SenderType.SYSTEM;
        };
        Message.MessageType type = message.role() == ConversationMessage.Role.TOOL
                ? Message.MessageType.TOOL_RESULT : Message.MessageType.TEXT;
        // A coluna de conteúdo é obrigatória; uma fala sem texto continua sendo uma fala.
        String content = message.content() == null ? "" : message.content();
        return new Message(message.id(), message.conversationId(), tenantId, sender, type, content, null,
                message.metadata() == null ? null : new HashMap<>(message.metadata()), message.timestamp());
    }

    private static ConversationMessage toConversationMessage(Message stored) {
        ConversationMessage.Role role = switch (stored.senderType()) {
            case USER -> ConversationMessage.Role.USER;
            case SYSTEM -> ConversationMessage.Role.SYSTEM;
            case AGENT, HUMAN_OPERATOR -> stored.messageType() == Message.MessageType.TOOL_RESULT
                    ? ConversationMessage.Role.TOOL : ConversationMessage.Role.ASSISTANT;
        };
        Map<String, Object> metadata = stored.metadata() == null
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(stored.metadata()));
        return new ConversationMessage(stored.id(), stored.conversationId(), role, stored.content(),
                stored.timestamp(), metadata);
    }

    /**
     * Builds a human-readable summary of the submitted form data.
     */
    private String buildFormDataSummary(Map<String, Object> formData) {
        if (formData == null || formData.isEmpty()) {
            return "(vazio)";
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
