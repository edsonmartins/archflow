package br.com.archflow.conversation.orchestrator;

import br.com.archflow.conversation.domain.Conversation;
import br.com.archflow.conversation.domain.ConversationRepository;
import br.com.archflow.conversation.domain.Message;
import br.com.archflow.conversation.guardrail.GuardrailChain;
import br.com.archflow.conversation.memory.WindowedChatMemoryProvider;
import br.com.archflow.conversation.persona.Persona;
import br.com.archflow.conversation.persona.PersonaResolver;
import br.com.archflow.conversation.prompt.PromptRegistry;
import br.com.archflow.conversation.prompt.PromptVersion;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * High-level orchestrator that wires the five conversation building blocks
 * (prompt registry, persona resolver, guardrail chains, sliding-window chat
 * memory, conversation repository) into a single message-processing pipeline.
 *
 * <p>This is the canonical entry point for building a SAC-style conversational
 * agent on top of ArchFlow. Products plug in their own {@link LlmCaller} (the
 * only piece that touches a real LLM) and get the full pipeline with one call:
 *
 * <pre>{@code
 * var orchestrator = ConversationOrchestrator.builder()
 *     .prompts(prompts)
 *     .personaResolver(personaResolver)
 *     .memoryProvider(memoryProvider)
 *     .repository(repository)
 *     .inputGuardrails(inputChain)
 *     .outputGuardrails(outputChain)
 *     .llmCaller(myLlmCaller)
 *     .promptVariables(Map.of("empresa", "Acme"))
 *     .build();
 *
 * var reply = orchestrator.process(ConversationOrchestrator.Request.of(
 *     "tenant-1", "user-42", null, "quero rastrear pedido 12345",
 *     Map.of("identified", true)
 * ));
 *
 * System.out.println(reply.text());
 * }</pre>
 *
 * <h2>Pipeline stages</h2>
 * <ol>
 *   <li>Load or create the {@link Conversation} (persistence boundary).</li>
 *   <li>Persist the user message.</li>
 *   <li>Run input {@link GuardrailChain}. If blocked, persist the block
 *       reply and return {@link ReplyStatus#BLOCKED_INPUT}.</li>
 *   <li>Use the possibly-redacted text for subsequent stages.</li>
 *   <li>Resolve the {@link Persona} via {@link PersonaResolver}.</li>
 *   <li>Fetch the active {@link PromptVersion} for the persona and render it
 *       with the configured variables.</li>
 *   <li>Load the sliding-window {@link ChatMemory} for this conversation and
 *       append the user message.</li>
 *   <li>Call the LLM via the provided {@link LlmCaller}.</li>
 *   <li>Run the output {@link GuardrailChain}. If blocked, persist the block
 *       reply and return {@link ReplyStatus#BLOCKED_OUTPUT}.</li>
 *   <li>Persist the (possibly redacted) agent reply and update chat memory.</li>
 *   <li>Return the result with all redaction reasons collected along the way.</li>
 * </ol>
 */
public class ConversationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ConversationOrchestrator.class);

    private final PromptRegistry prompts;
    private final PersonaResolver personaResolver;
    private final WindowedChatMemoryProvider memoryProvider;
    private final ConversationRepository repository;
    private final GuardrailChain inputGuardrails;
    private final GuardrailChain outputGuardrails;
    private final LlmCaller llmCaller;
    private final Map<String, Object> promptVariables;

    private ConversationOrchestrator(Builder b) {
        this.prompts = Objects.requireNonNull(b.prompts, "prompts");
        this.personaResolver = Objects.requireNonNull(b.personaResolver, "personaResolver");
        this.memoryProvider = Objects.requireNonNull(b.memoryProvider, "memoryProvider");
        this.repository = Objects.requireNonNull(b.repository, "repository");
        this.inputGuardrails = b.inputGuardrails != null ? b.inputGuardrails : new GuardrailChain(List.of());
        this.outputGuardrails = b.outputGuardrails != null ? b.outputGuardrails : new GuardrailChain(List.of());
        this.llmCaller = Objects.requireNonNull(b.llmCaller, "llmCaller");
        this.promptVariables = Map.copyOf(b.promptVariables);
    }

    /**
     * Processes a single user message through the full pipeline.
     */
    public Reply process(Request req) {
        Objects.requireNonNull(req, "request");

        // 1. Load or create conversation
        Conversation conv = loadOrCreate(req);

        // 2. Persist user message (audit log, keeps full history regardless of memory window)
        repository.addMessage(Message.userText(conv.id(), conv.tenantId(), req.message()));

        // 3. Input guardrails
        Map<String, Object> guardCtx = req.guardrailContext() != null
                ? new HashMap<>(req.guardrailContext())
                : new HashMap<>();
        GuardrailChain.ChainResult inR = inputGuardrails.evaluateInput(req.message(), guardCtx);
        if (inR.blocked()) {
            Message reply = Message.agentText(conv.id(), conv.tenantId(), inR.blockMessage());
            repository.addMessage(reply);
            log.debug("Input blocked by guardrail '{}'", inR.blockReason());
            return Reply.blockedInput(conv.id(), inR.blockReason(), inR.blockMessage());
        }

        String sanitizedUserMessage = inR.finalText();

        // 4. Persona
        Persona persona = personaResolver.resolve(conv.id(), sanitizedUserMessage)
                .orElseThrow(() -> new IllegalStateException(
                        "No persona resolved and no default configured"));
        if (!Objects.equals(persona.id(), conv.persona())) {
            conv = repository.save(conv.withPersona(persona.id()));
        }

        // 5. Prompt
        final Conversation convForPrompt = conv;
        PromptVersion pv = prompts.getActive(convForPrompt.tenantId(), persona.promptId())
                .orElseThrow(() -> new IllegalStateException(
                        "No active prompt for tenant=" + convForPrompt.tenantId()
                                + " promptId=" + persona.promptId()));
        Map<String, Object> vars = new HashMap<>(promptVariables);
        if (req.promptVariables() != null) vars.putAll(req.promptVariables());
        String systemPrompt = pv.render(vars);

        // 6. Memory
        ChatMemory memory = memoryProvider.getOrCreate(conv.tenantId(), conv.id());
        List<ChatMessage> historyBeforeUser = List.copyOf(memory.messages());
        memory.add(UserMessage.from(sanitizedUserMessage));

        // 7. LLM
        LlmResponse llmResponse;
        try {
            llmResponse = llmCaller.call(new LlmRequest(
                    conv.tenantId(),
                    conv.id(),
                    systemPrompt,
                    historyBeforeUser,
                    sanitizedUserMessage,
                    persona,
                    pv.version()
            ));
        } catch (RuntimeException e) {
            log.error("LLM call failed: {}", e.getMessage(), e);
            throw e;
        }

        String llmText = llmResponse.text();

        // 8. Output guardrails
        GuardrailChain.ChainResult outR = outputGuardrails.evaluateOutput(llmText, guardCtx);
        if (outR.blocked()) {
            Message reply = Message.agentText(conv.id(), conv.tenantId(), outR.blockMessage());
            repository.addMessage(reply);
            log.debug("Output blocked by guardrail '{}'", outR.blockReason());
            return Reply.blockedOutput(conv.id(), persona.id(), pv.version(),
                    outR.blockReason(), outR.blockMessage(),
                    mergeRedactionReasons(inR, outR));
        }

        String finalReply = outR.finalText();

        // 9. Persist agent reply + memory
        memory.add(AiMessage.from(finalReply));
        repository.addMessage(Message.agentText(conv.id(), conv.tenantId(), finalReply));

        return Reply.ok(conv.id(), persona.id(), pv.version(), finalReply,
                mergeRedactionReasons(inR, outR));
    }

    private Conversation loadOrCreate(Request req) {
        if (req.conversationId() != null) {
            Conversation existing = repository.findById(req.tenantId(), req.conversationId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Conversation not found: " + req.conversationId()));
            if (!existing.userId().equals(req.userId())) {
                throw new IllegalStateException(
                        "Conversation " + req.conversationId() + " belongs to a different user");
            }
            return existing;
        }
        return repository.save(Conversation.start(req.tenantId(), req.userId(), req.channel()));
    }

    private static List<String> mergeRedactionReasons(
            GuardrailChain.ChainResult in, GuardrailChain.ChainResult out) {
        if (in.redactionReasons().isEmpty()) return out.redactionReasons();
        if (out.redactionReasons().isEmpty()) return in.redactionReasons();
        List<String> merged = new java.util.ArrayList<>(in.redactionReasons());
        merged.addAll(out.redactionReasons());
        return List.copyOf(merged);
    }

    // ── Request / Reply ────────────────────────────────────────────

    public record Request(
            String tenantId,
            String userId,
            String conversationId,
            String channel,
            String message,
            Map<String, Object> guardrailContext,
            Map<String, Object> promptVariables
    ) {
        public Request {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(message, "message");
            if (channel == null) channel = "API";
        }

        public static Request of(String tenantId, String userId, String conversationId,
                                 String message, Map<String, Object> guardrailContext) {
            return new Request(tenantId, userId, conversationId, "API", message, guardrailContext, Map.of());
        }
    }

    public enum ReplyStatus {
        OK,
        BLOCKED_INPUT,
        BLOCKED_OUTPUT
    }

    public record Reply(
            String conversationId,
            String personaId,
            Integer promptVersion,
            String text,
            ReplyStatus status,
            String blockReason,
            List<String> redactionReasons
    ) {
        public static Reply ok(String convId, String personaId, int promptVersion,
                               String text, List<String> redactions) {
            return new Reply(convId, personaId, promptVersion, text, ReplyStatus.OK, null, redactions);
        }

        public static Reply blockedInput(String convId, String reason, String message) {
            return new Reply(convId, null, null, message, ReplyStatus.BLOCKED_INPUT, reason, List.of());
        }

        public static Reply blockedOutput(String convId, String personaId, int promptVersion,
                                          String reason, String message, List<String> redactions) {
            return new Reply(convId, personaId, promptVersion, message, ReplyStatus.BLOCKED_OUTPUT, reason, redactions);
        }

        public boolean isOk() {
            return status == ReplyStatus.OK;
        }
    }

    // ── LLM integration contract ───────────────────────────────────

    /**
     * The only piece of the pipeline that touches an actual LLM. Products
     * provide a lambda/method reference that calls their ChatModel of choice
     * (langchain4j, direct HTTP, mock, etc).
     */
    @FunctionalInterface
    public interface LlmCaller {
        LlmResponse call(LlmRequest request);
    }

    public record LlmRequest(
            String tenantId,
            String conversationId,
            String systemPrompt,
            List<ChatMessage> history,
            String userMessage,
            Persona persona,
            int promptVersion
    ) {}

    public record LlmResponse(String text, Map<String, Object> metadata) {
        public static LlmResponse of(String text) {
            return new LlmResponse(text, Map.of());
        }
    }

    // ── Builder ────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private PromptRegistry prompts;
        private PersonaResolver personaResolver;
        private WindowedChatMemoryProvider memoryProvider;
        private ConversationRepository repository;
        private GuardrailChain inputGuardrails;
        private GuardrailChain outputGuardrails;
        private LlmCaller llmCaller;
        private Map<String, Object> promptVariables = Map.of();

        public Builder prompts(PromptRegistry p) { this.prompts = p; return this; }
        public Builder personaResolver(PersonaResolver r) { this.personaResolver = r; return this; }
        public Builder memoryProvider(WindowedChatMemoryProvider m) { this.memoryProvider = m; return this; }
        public Builder repository(ConversationRepository r) { this.repository = r; return this; }
        public Builder inputGuardrails(GuardrailChain c) { this.inputGuardrails = c; return this; }
        public Builder outputGuardrails(GuardrailChain c) { this.outputGuardrails = c; return this; }
        public Builder llmCaller(LlmCaller l) { this.llmCaller = l; return this; }
        public Builder promptVariables(Map<String, Object> vars) {
            this.promptVariables = vars == null ? Map.of() : Map.copyOf(vars);
            return this;
        }

        /**
         * Sets a single prompt variable convenience.
         */
        public Builder promptVariable(String key, Object value) {
            Map<String, Object> copy = new HashMap<>(this.promptVariables);
            copy.put(key, value);
            this.promptVariables = Map.copyOf(copy);
            return this;
        }

        public ConversationOrchestrator build() {
            return new ConversationOrchestrator(this);
        }
    }

    // ── Accessors for introspection/testing ───────────────────────

    public PromptRegistry prompts() { return prompts; }
    public PersonaResolver personaResolver() { return personaResolver; }
    public WindowedChatMemoryProvider memoryProvider() { return memoryProvider; }
    public ConversationRepository repository() { return repository; }
    public GuardrailChain inputGuardrails() { return inputGuardrails; }
    public GuardrailChain outputGuardrails() { return outputGuardrails; }
}
