package br.com.archflow.api.web.conversation;

import br.com.archflow.api.conversation.ConversationController;
import br.com.archflow.api.conversation.dto.ConversationResponse;
import br.com.archflow.api.conversation.dto.ResumeRequest;
import br.com.archflow.api.conversation.dto.SuspendRequest;
import br.com.archflow.conversation.form.FormData;
import br.com.archflow.conversation.message.ConversationMessage;
import br.com.archflow.conversation.service.ConversationService;
import br.com.archflow.conversation.state.SuspendedConversation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/conversations")
public class SpringConversationController {

    private final ConversationController delegate;
    private final ConversationService conversationService;

    public SpringConversationController(ConversationController delegate, ConversationService conversationService) {
        this.delegate = delegate;
        this.conversationService = conversationService;
    }

    @GetMapping
    public PagedConversationResponse listConversations(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String since,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20", name = "pageSize") int pageSize) {
        Instant sinceInstant = parseInstant(since);

        List<ConversationDto> filtered = conversationService.listConversationIds().stream()
                .map(this::toConversation)
                .flatMap(Optional::stream)
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

    @PostMapping("/suspend")
    public ConversationResponse suspend(@RequestBody SuspendRequest request) {
        return delegate.suspend(request);
    }

    @PostMapping("/resume")
    public ConversationResponse resume(@RequestBody ResumeRequest request) {
        return delegate.resume(request);
    }

    @GetMapping("/{id}")
    public ConversationDto getConversation(@PathVariable String id) {
        return toConversation(id)
                .orElseThrow(() -> new ConversationController.NotFoundException("Conversation not found: " + id));
    }

    @GetMapping("/{id}/messages")
    public List<MessageDto> getMessages(@PathVariable String id) {
        List<ConversationMessage> messages = conversationService.getMessages(id);
        if (messages.isEmpty() && conversationService.getById(id).isEmpty()) {
            throw new ConversationController.NotFoundException("Conversation not found: " + id);
        }
        return messages.stream().map(this::toMessage).toList();
    }

    @PostMapping("/{id}/messages")
    public SendMessageResponse sendMessage(@PathVariable String id, @RequestBody SendMessageRequest request) {
        ConversationMessage message = ConversationMessage.user(id, request.content());
        conversationService.addMessage(message);
        return new SendMessageResponse(message.id());
    }

    @DeleteMapping("/{id}")
    public void cancel(@PathVariable String id) {
        delegate.cancel(id);
    }

    @ExceptionHandler(ConversationController.NotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(ConversationController.NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    private Optional<ConversationDto> toConversation(String conversationId) {
        Optional<SuspendedConversation> state = conversationService.getById(conversationId);
        List<ConversationMessage> messages = conversationService.getMessages(conversationId);

        if (state.isEmpty() && messages.isEmpty()) {
            return Optional.empty();
        }

        Instant createdAt = state.map(SuspendedConversation::getCreatedAt)
                .orElseGet(() -> messages.isEmpty() ? Instant.now() : messages.get(0).timestamp());
        Instant updatedAt = messages.isEmpty()
                ? createdAt
                : messages.get(messages.size() - 1).timestamp();

        Map<String, Object> context = state.map(SuspendedConversation::getContext).orElse(Map.of());
        String lastMessage = messages.isEmpty() ? null : messages.get(messages.size() - 1).content();

        return Optional.of(new ConversationDto(
                conversationId,
                state.map(SuspendedConversation::getWorkflowId).orElse(stringValue(context.get("workflowId"))),
                stringValue(context.getOrDefault("tenantId", "default")),
                stringValue(context.get("userId")),
                stringValue(context.getOrDefault("channel", "API")),
                state.map(s -> mapStatus(s.getStatus())).orElse("ACTIVE"),
                stringValue(context.get("persona")),
                stringValue(context.get("title")),
                state.map(SuspendedConversation::getResumeToken).orElse(null),
                state.map(SuspendedConversation::getForm).map(this::toFormData).orElse(null),
                createdAt,
                updatedAt,
                messages.size(),
                lastMessage
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
            String lastMessage
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
