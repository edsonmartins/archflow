package br.com.archflow.api.web.conversation;

import br.com.archflow.api.config.TenantContext;
import br.com.archflow.api.config.WorkflowPermissionInterceptor;
import br.com.archflow.api.conversation.ConversationController;
import br.com.archflow.api.conversation.ConversationResumeService;
import br.com.archflow.api.conversation.dto.ConversationResponse;
import br.com.archflow.api.conversation.dto.ResumeRequest;
import br.com.archflow.api.conversation.dto.SuspendRequest;
import br.com.archflow.api.web.approval.SpringApprovalController;
import br.com.archflow.conversation.domain.Conversation;
import br.com.archflow.conversation.form.FormData;
import br.com.archflow.conversation.form.FormRegistry;
import br.com.archflow.conversation.message.ConversationMessage;
import br.com.archflow.conversation.service.ConversationService;
import br.com.archflow.conversation.state.SuspendedConversation;
import br.com.archflow.model.security.ApiKeyScope;
import br.com.archflow.model.tenant.DefaultTenant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Conversas da UI.
 *
 * <p><b>Tenant (RCHFLW-71).</b> Cada conversa tem um dono, e lista, detalhe, mensagens, envio,
 * suspensão, retomada e cancelamento só alcançam as do tenant da requisição ({@link TenantContext});
 * a de outro tenant responde 404, como se não existisse. Antes o store de mensagens era um mapa
 * global e a lista devolvia as conversas de todos os tenants do processo.
 *
 * <p>O dono fica gravado onde a conversa já vive, sem um registro paralelo que pudesse divergir
 * dela: nos metadados ({@link #TENANT_METADATA}) da mensagem que a abre — a de sistema do
 * {@code POST} ou do {@code POST /suspend} que a cria, ou a primeira mensagem do usuário quando ela
 * nasce por um envio — e, numa conversa suspensa, também no contexto do estado (que o store JDBC
 * persiste). Conversa sem nenhum dos dois é anterior a esta regra e pertence ao tenant padrão
 * ({@link DefaultTenant#ID}).
 *
 * <p>Toda conversa criada por este controller precisa da mensagem marcada, e não só do contexto: o
 * estado suspenso some no cancelamento, na expiração e na limpeza, e as mensagens que o serviço
 * acrescenta sozinho ("Conversation cancelled", "Form submitted: ...") não levam tenant — sem a
 * marca, a conversa cairia no tenant padrão, com o formulário preenchido dentro.
 */
@RestController
@RequestMapping("/api/conversations")
public class SpringConversationController {

    /** Chave do tenant dono nos metadados da mensagem que abre a conversa e no contexto da suspensa. */
    public static final String TENANT_METADATA = "tenantId";

    /**
     * Tamanho máximo do id escolhido pelo cliente: o das colunas {@code conversations.id},
     * {@code conversation_messages.conversation_id} e {@code suspended_conversations.conversation_id}
     * (VARCHAR(64), migrations V2_1/V2_2). Conferido aqui, em memória também: com JDBC um id maior falhava
     * no banco, na primeira mensagem, como 500; em dev era aceito — o mesmo pedido não pode valer num
     * ambiente e quebrar no outro.
     */
    static final int MAX_CONVERSATION_ID_LENGTH = 64;

    private final ConversationController delegate;
    private final ConversationService conversationService;
    private final br.com.archflow.api.conversation.ConversationReplyService replyService;
    private final ConversationResumeService resumeService;
    private final FormRegistry forms;

    /**
     * A mesma propriedade que liga o filtro JWT e os interceptors de papel: desligada (dev), o envio de
     * formulário não confere permissão nenhuma, como antes.
     */
    @Value("${archflow.security.auth.enabled:false}")
    private boolean authEnabled;

    public SpringConversationController(ConversationController delegate,
                                        ConversationService conversationService,
                                        br.com.archflow.api.conversation.ConversationReplyService replyService,
                                        ConversationResumeService resumeService,
                                        FormRegistry forms) {
        this.delegate = delegate;
        this.conversationService = conversationService;
        this.replyService = replyService;
        this.resumeService = resumeService;
        this.forms = forms;
    }

    /**
     * Cria uma conversa vazia (registrada por uma mensagem de sistema) e
     * devolve o id — dá à UI um caminho para iniciar conversas.
     */
    @PostMapping
    public ResponseEntity<Map<String, String>> create() {
        String id = java.util.UUID.randomUUID().toString();
        conversationService.addMessage(openingMessage(id, ConversationMessage.Role.SYSTEM, "Conversa iniciada"));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @GetMapping
    public PagedConversationResponse listConversations(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String since,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20", name = "pageSize") int pageSize) {
        Instant sinceInstant = parseInstant(since);
        String tenantId = TenantContext.currentTenantId();

        List<ConversationDto> filtered = conversationService.listConversationIds(tenantId).stream()
                .map(this::toConversation)
                .flatMap(Optional::stream)
                .filter(conversation -> tenantId.equals(conversation.tenantId()))
                .filter(conversation -> matchesStatus(conversation, status))
                .filter(conversation -> matchesSince(conversation, sinceInstant))
                .filter(conversation -> matchesSearch(conversation, search))
                .sorted(Comparator.comparing(ConversationDto::updatedAt).reversed())
                .toList();

        int safePage = Math.max(page, 0);
        int safePageSize = Math.max(pageSize, 1);
        int fromIndex = Math.min(safePage * safePageSize, filtered.size());
        int toIndex = Math.min(fromIndex + safePageSize, filtered.size());

        return new PagedConversationResponse(
                filtered.subList(fromIndex, toIndex),
                safePage,
                safePageSize,
                filtered.size()
        );
    }

    /**
     * Suspender uma conversa existente de outro tenant é 404. Uma conversa nova nasce suspensa com o
     * tenant de quem pediu no contexto (ver {@code ConversationControllerImpl.suspend}) e numa mensagem
     * de abertura marcada, gravada antes de delegar: o contexto não basta, porque some com o estado no
     * cancelamento ou na expiração (ver o javadoc da classe), e gravar antes garante o dono mesmo que
     * a suspensão falhe depois de salvar o estado.
     *
     * <p>O formulário ({@code formId}) e a execução que o envio dele vai continuar são conferidos
     * antes de tudo (RCHFLW-67): um pedido inválido é 400/409 e não cria conversa nenhuma. Suspensa, a
     * conversa publica o formulário no stream do chat — o {@code ConversationManager} publicava para
     * uma lista de assinantes vazia.</p>
     */
    @PostMapping("/suspend")
    public ConversationResponse suspend(@RequestBody SuspendRequest request) {
        String tenantId = TenantContext.currentTenantId();
        if (request != null) {
            forms.require(request.formId());
        }
        if (request != null && request.conversationId() != null) {
            String id = request.conversationId();
            boolean existed = exists(id);
            if (existed && !belongsToCaller(id)) {
                throw notFound(id);
            }
            requireNotCancelled(id);
            resumeService.validateTarget(tenantId, request.workflowId(), request.executionId());
            if (!existed) {
                conversationService.addMessage(
                        openingMessage(id, ConversationMessage.Role.SYSTEM, "Conversa iniciada"));
            }
        }
        ConversationResponse response = delegate.suspend(request);
        conversationService.getById(response.conversationId())
                .filter(state -> state.getForm() != null)
                .ifPresent(state -> resumeService.announceForm(tenantId, response.conversationId(),
                        response.resumeToken(), formEvent(toFormData(state.getForm()),
                                state.getWorkflowExecutionId())));
        return response;
    }

    /**
     * O token de retomada não basta: a conversa também precisa ser do tenant de quem retoma. A
     * resposta para o token de outro tenant é a mesma do token inválido.
     *
     * <p>O envio continua a execução associada com os dados do formulário — ou, sem execução, pede a
     * resposta do agente — e a resposta chega pelo stream do chat (RCHFLW-67). Quem enviou é o do
     * token, como o decisor da fila de aprovações, e fica gravado como quem decidiu o portão.</p>
     */
    @PostMapping("/resume")
    public ConversationResponse resume(@RequestBody ResumeRequest request) {
        if (request == null || request.resumeToken() == null) {
            throw new IllegalArgumentException("resumeToken é obrigatório");
        }
        Optional<SuspendedConversation> state = conversationService.getByToken(request.resumeToken());
        if (state.isPresent() && !belongsToCaller(state.get().getConversationId())) {
            throw new ConversationController.NotFoundException(
                    "Conversa não encontrada ou expirada para o token: " + request.resumeToken());
        }
        if (authEnabled && state.isPresent() && state.get().getWorkflowExecutionId() != null
                && !state.get().getWorkflowExecutionId().isBlank()) {
            // Ligado a uma execução, o envio aprova o portão dela pelo mesmo caminho da fila de aprovações
            // (ConversationResumeService.resume → submitInput), e quem enviou fica gravado como o decisor.
            // Exige a permissão de decidir lá (execution:manage); sem isto o formulário do chat contornava
            // a regra do POST /api/approvals/{id} (QA2 P1). Só o controller sabe se há execução: o
            // interceptor vê o caminho, não a conversa.
            Optional<Map<String, Object>> refusal = WorkflowPermissionInterceptor.refusal(currentRequest(),
                    ApiKeyScope.EXECUTION_MANAGE.getPermission());
            if (refusal.isPresent()) {
                throw new PermissionDeniedException(refusal.get());
            }
        }
        return resumeService.resume(request, TenantContext.currentTenantId(),
                SpringApprovalController.authenticatedDecider(currentRequest()));
    }

    private static jakarta.servlet.http.HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes sra
                ? sra.getRequest() : null;
    }

    /** O {@code 403} do envio de formulário sem a permissão, com o corpo do interceptor de papéis. */
    static final class PermissionDeniedException extends RuntimeException {
        private final transient Map<String, Object> body;

        PermissionDeniedException(Map<String, Object> body) {
            super(String.valueOf(body.get("message")));
            this.body = body;
        }
    }

    @ExceptionHandler(PermissionDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(PermissionDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ex.body);
    }

    /**
     * As chaves do evento {@code interaction/form} do protocolo, com os campos do {@code formData} — e a
     * execução que o envio continua, quando há uma: com ela o chat sabe que enviar exige
     * {@code execution:manage} (ver {@link #resume}) e desabilita o formulário de quem não a tem, em vez
     * de oferecer um envio que o servidor recusa (QA2 RCHFLW-136).
     */
    private static Map<String, Object> formEvent(FormDataDto form, String executionId) {
        Map<String, Object> event = new LinkedHashMap<>();
        if (executionId != null && !executionId.isBlank()) {
            event.put("executionId", executionId);
        }
        event.put("formId", form.id());
        event.put("title", form.title());
        event.put("description", form.description());
        event.put("fields", form.fields());
        event.put("submitLabel", form.submitLabel());
        event.put("cancelLabel", form.cancelLabel());
        return event;
    }

    @GetMapping("/{id}")
    public ConversationDto getConversation(@PathVariable String id) {
        String tenantId = TenantContext.currentTenantId();
        return toConversation(id)
                .filter(conversation -> tenantId.equals(conversation.tenantId()))
                .orElseThrow(() -> notFound(id));
    }

    @GetMapping("/{id}/messages")
    public List<MessageDto> getMessages(@PathVariable String id) {
        requireOwned(id);
        return conversationService.getMessages(id).stream().map(this::toMessage).toList();
    }

    /**
     * Enviar para a conversa de outro tenant é 404. Enviar para um id ainda sem conversa a cria — como
     * antes —, e então a primeira mensagem leva o tenant de quem enviou, que passa a ser o dono. Uma
     * conversa cancelada não recebe novos turnos (409): antes ela aceitava a mensagem e o agente
     * respondia (RCHFLW-194).
     *
     * <p>A mensagem aceita vai ao stream da conversa ({@code chat/message}, papel {@code user}, com o
     * id gravado): um segundo visualizador da mesma conversa só recebia as respostas do assistente.</p>
     */
    @PostMapping("/{id}/messages")
    public SendMessageResponse sendMessage(@PathVariable String id,
                                           @RequestBody SendMessageRequest request) {
        String tenantId = TenantContext.currentTenantId();
        ConversationMessage message;
        if (exists(id)) {
            if (!belongsToCaller(id)) {
                throw notFound(id);
            }
            requireNotCancelled(id);
            message = ConversationMessage.user(id, request.content());
        } else {
            message = openingMessage(id, ConversationMessage.Role.USER, request.content());
        }
        conversationService.addMessage(message);
        replyService.publishUserMessage(id, tenantId, message);
        // Dispara a resposta do agente (assíncrona) — entregue via SSE em
        // GET /api/stream/{tenant}/{conversationId}, no canal do tenant dono.
        replyService.replyAsync(id, tenantId);
        return new SendMessageResponse(message.id());
    }

    /**
     * Cancela a conversa, suspensa ou ativa, e responde 204 — sem corpo, como o cliente HTTP da UI
     * espera. Era um 200 de corpo vazio, que o cliente tentava ler como JSON: o cancelamento acontecia,
     * mas o chat mostrava erro e o badge seguia SUSPENSA (RCHFLW-194). O formulário sai também dos
     * outros visualizadores ({@code interaction/cancel}), e o aviso de sistema que o cancelamento grava vai
     * ao stream deles — antes ficava só no histórico, e quem acompanhava não via que a conversa acabou.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable String id) {
        requireOwned(id);
        String tenantId = TenantContext.currentTenantId();
        int before = conversationService.getMessages(id).size();
        delegate.cancel(id);
        // O que o cancelamento gravou (o aviso; nada num segundo cancelamento) — o serviço não devolve a
        // fala, então ela é lida da transcrição.
        List<ConversationMessage> after = conversationService.getMessages(id);
        after.subList(Math.min(before, after.size()), after.size()).stream()
                .filter(m -> m.role() == ConversationMessage.Role.SYSTEM)
                .forEach(m -> replyService.publishStoredNotice(id, tenantId, m));
        resumeService.announceFormWithdrawn(tenantId, id);
    }

    /** Cancelada, a conversa não recebe mensagem, suspensão nem formulário novo. */
    private void requireNotCancelled(String conversationId) {
        if (conversationService.getRecord(conversationId)
                .filter(r -> r.status() == Conversation.ConversationStatus.CANCELLED).isPresent()) {
            throw new IllegalStateException("A conversa " + conversationId
                    + " foi cancelada e não recebe novas mensagens");
        }
    }

    @ExceptionHandler(ConversationController.NotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(ConversationController.NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    private boolean exists(String conversationId) {
        return conversationService.getRecord(conversationId).isPresent()
                || conversationService.getById(conversationId).isPresent();
    }

    private boolean belongsToCaller(String conversationId) {
        return TenantContext.currentTenantId().equals(ownerOf(conversationService.getRecord(conversationId),
                conversationService.getById(conversationId), conversationService.getMessages(conversationId)));
    }

    /** Inexistente e de outro tenant dão o mesmo 404: a resposta não revela que o id existe. */
    private void requireOwned(String conversationId) {
        if (!exists(conversationId) || !belongsToCaller(conversationId)) {
            throw notFound(conversationId);
        }
    }

    /**
     * Mensagem que abre a conversa, marcada com o tenant da requisição — é ela que registra o dono. Toda
     * conversa nova passa por aqui, então é aqui que o id do cliente é conferido (400, antes de qualquer
     * gravação).
     */
    private static ConversationMessage openingMessage(String conversationId, ConversationMessage.Role role,
                                                      String content) {
        if (conversationId.length() > MAX_CONVERSATION_ID_LENGTH) {
            throw new IllegalArgumentException("O id da conversa tem " + conversationId.length()
                    + " caracteres; o máximo é " + MAX_CONVERSATION_ID_LENGTH);
        }
        return new ConversationMessage(java.util.UUID.randomUUID().toString(), conversationId, role,
                content, Instant.now(), Map.of(TENANT_METADATA, TenantContext.currentTenantId()));
    }

    private static ConversationController.NotFoundException notFound(String conversationId) {
        return new ConversationController.NotFoundException("Conversa não encontrada: " + conversationId);
    }

    /**
     * Dono da conversa: o tenant do registro durável, gravado com a primeira mensagem (RCHFLW-181); sem
     * registro, a regra de antes ({@link #ownerOf(Optional, List)}).
     */
    static String ownerOf(Optional<Conversation> record, Optional<SuspendedConversation> state,
                          List<ConversationMessage> messages) {
        return record.map(Conversation::tenantId).orElseGet(() -> ownerOf(state, messages));
    }

    /**
     * Dono da conversa: o tenant gravado na primeira mensagem que o traz (a que abriu a conversa);
     * senão o do contexto da conversa suspensa; senão o tenant padrão — conversa anterior ao tenant.
     */
    static String ownerOf(Optional<SuspendedConversation> state, List<ConversationMessage> messages) {
        for (ConversationMessage message : messages) {
            Object tenant = message.metadata() == null ? null : message.metadata().get(TENANT_METADATA);
            if (tenant instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return DefaultTenant.orDefault(state.map(SuspendedConversation::getContext)
                .map(SpringConversationController::tenantOfContext)
                .orElse(null));
    }

    /**
     * Tenant gravado no contexto do estado. A retomada ({@link SuspendedConversation#resume}) não o
     * mantém no topo: move o contexto inteiro para {@code originalContext}. Sem descer até ele, uma
     * conversa retomada cujas mensagens não estão mais no store em memória (reinício do processo, com
     * o estado no JDBC) cairia no tenant padrão.
     */
    private static String tenantOfContext(Map<String, Object> context) {
        Map<?, ?> current = context;
        // Limite de profundidade só por defesa: cada retomada aninha um nível, e uma conversa só é
        // retomada uma vez (o RESUMED não é mais WAITING).
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current.get(TENANT_METADATA) instanceof String s && !s.isBlank()) {
                return s;
            }
            current = current.get("originalContext") instanceof Map<?, ?> original ? original : null;
        }
        return null;
    }

    private Optional<ConversationDto> toConversation(String conversationId) {
        Optional<Conversation> record = conversationService.getRecord(conversationId);
        Optional<SuspendedConversation> state = conversationService.getById(conversationId);
        List<ConversationMessage> messages = conversationService.getMessages(conversationId);

        if (record.isEmpty() && state.isEmpty() && messages.isEmpty()) {
            return Optional.empty();
        }

        // A criação é a da conversa (registro, primeira mensagem), não a da suspensão: o "Criada em"
        // passava a ser a hora em que ela foi suspensa (RCHFLW-194).
        Instant createdAt = record.map(Conversation::createdAt)
                .or(() -> messages.isEmpty() ? Optional.empty() : Optional.of(messages.get(0).timestamp()))
                .or(() -> state.map(SuspendedConversation::getCreatedAt))
                .orElseGet(Instant::now);
        Instant updatedAt = messages.isEmpty()
                ? createdAt
                : messages.get(messages.size() - 1).timestamp();

        Map<String, Object> context = state.map(SuspendedConversation::getContext).orElse(Map.of());
        String lastMessage = messages.isEmpty() ? null : messages.get(messages.size() - 1).content();

        return Optional.of(new ConversationDto(
                conversationId,
                state.map(SuspendedConversation::getWorkflowId).orElse(stringValue(context.get("workflowId"))),
                ownerOf(record, state, messages),
                stringValue(context.get("userId")),
                stringValue(context.getOrDefault("channel", "API")),
                statusOf(record, state),
                stringValue(context.get("persona")),
                stringValue(context.get("title")),
                state.map(SuspendedConversation::getResumeToken).orElse(null),
                state.map(SuspendedConversation::getForm).map(this::toFormData).orElse(null),
                createdAt,
                updatedAt,
                messages.size(),
                lastMessage,
                state.map(SuspendedConversation::getWorkflowExecutionId).filter(id -> !id.isBlank()).orElse(null)
        ));
    }

    private boolean matchesStatus(ConversationDto conversation, String status) {
        return status == null || status.isBlank() || "ALL".equalsIgnoreCase(status) || conversation.status().equalsIgnoreCase(status);
    }

    private boolean matchesSince(ConversationDto conversation, Instant since) {
        return since == null || !conversation.updatedAt().isBefore(since);
    }

    private boolean matchesSearch(ConversationDto conversation, String search) {
        if (search == null || search.isBlank()) return true;
        String normalized = search.toLowerCase(Locale.ROOT);
        return contains(conversation.id(), normalized)
                || contains(conversation.workflowId(), normalized)
                || contains(conversation.tenantId(), normalized)
                || contains(conversation.userId(), normalized)
                || contains(conversation.channel(), normalized)
                || contains(conversation.persona(), normalized)
                || contains(conversation.title(), normalized)
                || contains(conversation.lastMessage(), normalized);
    }

    private boolean contains(String value, String search) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(search);
    }

    private Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Instant.parse(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * O cancelamento do registro vence: o estado de suspensão some ao cancelar, e sem o registro a
     * conversa voltava a ser ACTIVE (RCHFLW-194). Fora isso, o da suspensão, como antes; sem suspensão,
     * o do registro.
     */
    private String statusOf(Optional<Conversation> record, Optional<SuspendedConversation> state) {
        if (record.filter(r -> r.status() == Conversation.ConversationStatus.CANCELLED).isPresent()) {
            return Conversation.ConversationStatus.CANCELLED.name();
        }
        return state.map(s -> mapStatus(s.getStatus()))
                .orElseGet(() -> record.map(r -> r.status().name()).orElse("ACTIVE"));
    }

    private String mapStatus(SuspendedConversation.ConversationStatus status) {
        return switch (status) {
            case WAITING -> "SUSPENDED";
            case RESUMED -> "ACTIVE";
            case CANCELLED -> "CANCELLED";
            case TIMED_OUT -> "CLOSED";
            case COMPLETED -> "COMPLETED";
        };
    }

    private FormDataDto toFormData(FormData formData) {
        return new FormDataDto(
                formData.getId(),
                formData.getTitle(),
                formData.getDescription(),
                formData.getFields().stream().map(this::toFormField).toList(),
                formData.getSubmitLabel(),
                formData.getCancelLabel()
        );
    }

    private FormFieldDto toFormField(FormData.FormField field) {
        return new FormFieldDto(
                field.getName(),
                field.getLabel(),
                field.getType().toUpperCase(Locale.ROOT).replace('-', '_'),
                field.isRequired(),
                field.getPlaceholder(),
                field.getDefaultValue() != null ? String.valueOf(field.getDefaultValue()) : null,
                field.getOptions() == null ? null : field.getOptions().stream()
                        .map(option -> new FormOptionDto(option.value(), option.label()))
                        .toList(),
                field.getDescription()
        );
    }

    private MessageDto toMessage(ConversationMessage message) {
        return new MessageDto(
                message.id(),
                message.role().name().toLowerCase(Locale.ROOT),
                message.content(),
                message.timestamp(),
                message.metadata()
        );
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public record PagedConversationResponse(
            List<ConversationDto> items,
            int page,
            int pageSize,
            int total
    ) {}

    public record ConversationDto(
            String id,
            String workflowId,
            String tenantId,
            String userId,
            String channel,
            String status,
            String persona,
            String title,
            String resumeToken,
            FormDataDto formData,
            Instant createdAt,
            Instant updatedAt,
            int messageCount,
            String lastMessage,
            /**
             * A execução que o formulário pendente continua, ou {@code null}. Com ela, enviar o formulário
             * exige {@code execution:manage}, como decidir na fila de aprovações (ver {@link #resume}).
             */
            String executionId
    ) {}

    public record FormDataDto(
            String id,
            String title,
            String description,
            List<FormFieldDto> fields,
            String submitLabel,
            String cancelLabel
    ) {}

    public record FormFieldDto(
            String id,
            String label,
            String type,
            boolean required,
            String placeholder,
            String defaultValue,
            List<FormOptionDto> options,
            String description
    ) {}

    public record FormOptionDto(String value, String label) {}

    public record MessageDto(
            String id,
            String role,
            String content,
            Instant timestamp,
            Map<String, Object> metadata
    ) {}

    public record SendMessageRequest(String content) {}

    public record SendMessageResponse(String messageId) {}
}
