package br.com.archflow.conversation.e2e;

import br.com.archflow.conversation.domain.Conversation;
import br.com.archflow.conversation.domain.ConversationRepository;
import br.com.archflow.conversation.domain.InMemoryConversationRepository;
import br.com.archflow.conversation.guardrail.AgentGuardrail;
import br.com.archflow.conversation.guardrail.GuardrailChain;
import br.com.archflow.conversation.guardrail.GuardrailResult;
import br.com.archflow.conversation.guardrail.builtin.IdentificationGuardrail;
import br.com.archflow.conversation.guardrail.builtin.PiiRedactionGuardrail;
import br.com.archflow.conversation.guardrail.builtin.ProfanityGuardrail;
import br.com.archflow.conversation.memory.WindowedChatMemoryProvider;
import br.com.archflow.conversation.orchestrator.ConversationOrchestrator;
import br.com.archflow.conversation.orchestrator.ConversationOrchestrator.LlmCaller;
import br.com.archflow.conversation.orchestrator.ConversationOrchestrator.LlmResponse;
import br.com.archflow.conversation.orchestrator.ConversationOrchestrator.Reply;
import br.com.archflow.conversation.orchestrator.ConversationOrchestrator.ReplyStatus;
import br.com.archflow.conversation.orchestrator.ConversationOrchestrator.Request;
import br.com.archflow.conversation.persona.Persona;
import br.com.archflow.conversation.persona.PersonaResolver;
import br.com.archflow.conversation.prompt.InMemoryPromptRegistry;
import br.com.archflow.conversation.prompt.PromptRegistry;
import dev.langchain4j.memory.ChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end test driving the real {@link ConversationOrchestrator} through
 * 12 SAC scenarios. The only stub is the LLM callback; every other component
 * (prompts, persona, guardrails, memory, repository) is the production
 * in-memory implementation.
 */
class ConversationGapsE2ETest {

    private PromptRegistry prompts;
    private WindowedChatMemoryProvider memoryProvider;
    private ConversationRepository repo;
    private PersonaResolver personaResolver;
    private GuardrailChain inputChain;
    private GuardrailChain outputChain;
    private RecordingLlmCaller llmCaller;
    private ConversationOrchestrator orchestrator;

    private static final String TENANT = "acme";
    private static final String USER = "+5511999999999";

    private static final class RecordingLlmCaller implements LlmCaller {
        private final List<ConversationOrchestrator.LlmRequest> requests = new ArrayList<>();
        private java.util.function.Function<ConversationOrchestrator.LlmRequest, String> behavior =
                r -> "default response";

        @Override
        public LlmResponse call(ConversationOrchestrator.LlmRequest request) {
            requests.add(request);
            return LlmResponse.of(behavior.apply(request));
        }

        void setBehavior(java.util.function.Function<ConversationOrchestrator.LlmRequest, String> b) {
            this.behavior = b;
        }

        void reset() {
            requests.clear();
            behavior = r -> "default response";
        }
    }

    @BeforeEach
    void setUp() {
        prompts = new InMemoryPromptRegistry();
        memoryProvider = new WindowedChatMemoryProvider(6);
        repo = new InMemoryConversationRepository();
        llmCaller = new RecordingLlmCaller();

        prompts.register(TENANT, "sac.tracking",
                "Você é um agente SAC da {{empresa}} especializado em rastreamento. " +
                        "Responda o cliente {{nome}} de forma objetiva.");
        prompts.register(TENANT, "sac.complaint",
                "Você é um agente SAC da {{empresa}} que lida com reclamações com empatia.");
        prompts.register(TENANT, "sac.general",
                "Você é um assistente genérico da {{empresa}}.");

        Persona tracking = Persona.of("order_tracking", "Rastreamento", "sac.tracking",
                List.of("tracking_pedido", "consultar_pedidos_cliente"),
                "rastrear", "\\bentrega\\b", "\\bpedido\\b");
        Persona complaint = Persona.of("complaint", "Reclamação", "sac.complaint",
                List.of("criar_ticket_reclamacao"),
                "reclamação", "reclamar", "atras[ao]");
        Persona general = Persona.of("general", "Geral", "sac.general", List.of());

        personaResolver = new PersonaResolver(List.of(tracking, complaint), general);

        inputChain = new GuardrailChain(List.of(
                new ProfanityGuardrail(),
                new IdentificationGuardrail()
        ));
        outputChain = new GuardrailChain(List.of(
                new PiiRedactionGuardrail(),
                new ProfanityGuardrail()
        ));

        orchestrator = ConversationOrchestrator.builder()
                .prompts(prompts)
                .personaResolver(personaResolver)
                .memoryProvider(memoryProvider)
                .repository(repo)
                .inputGuardrails(inputChain)
                .outputGuardrails(outputChain)
                .llmCaller(llmCaller)
                .promptVariable("empresa", "Acme")
                .promptVariable("nome", "Cliente")
                .build();
    }

    // ── Helpers ────────────────────────────────────────────────────

    private Request userTurn(String conversationId, String message, boolean identified) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("identified", identified);
        return Request.of(TENANT, USER, conversationId, message, ctx);
    }

    // ── Tests ──────────────────────────────────────────────────────

    @Test
    @DisplayName("1. Full happy path")
    void fullHappyPath() {
        llmCaller.setBehavior(r -> "Seu pedido 12345 está em trânsito, previsão 2026-04-15.");

        Reply reply = orchestrator.process(userTurn(null, "quero rastrear pedido 12345", true));

        assertThat(reply.isOk()).isTrue();
        assertThat(reply.status()).isEqualTo(ReplyStatus.OK);
        assertThat(reply.personaId()).isEqualTo("order_tracking");
        assertThat(reply.text()).contains("em trânsito");
        assertThat(reply.redactionReasons()).isEmpty();

        // Persona was saved on the conversation
        assertThat(repo.findById(TENANT, reply.conversationId()).orElseThrow().persona())
                .isEqualTo("order_tracking");

        // Audit log has user + agent message
        assertThat(repo.countMessages(TENANT, reply.conversationId())).isEqualTo(2);

        // LLM received rendered system prompt with variables substituted
        assertThat(llmCaller.requests).hasSize(1);
        var llmReq = llmCaller.requests.get(0);
        assertThat(llmReq.systemPrompt()).contains("Acme").doesNotContain("{{empresa}}");
        assertThat(llmReq.userMessage()).isEqualTo("quero rastrear pedido 12345");
        assertThat(llmReq.persona().id()).isEqualTo("order_tracking");
        assertThat(llmReq.promptVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("2. Identification guardrail blocks business op without CNPJ")
    void identificationGuardrailBlocks() {
        llmCaller.setBehavior(r -> { throw new AssertionError("LLM should not be called"); });

        Reply reply = orchestrator.process(userTurn(null, "quero rastrear meu pedido", false));

        assertThat(reply.status()).isEqualTo(ReplyStatus.BLOCKED_INPUT);
        assertThat(reply.blockReason()).isEqualTo("missing_identification");
        assertThat(reply.text()).contains("CNPJ");
        assertThat(llmCaller.requests).isEmpty();
        assertThat(repo.countMessages(TENANT, reply.conversationId())).isEqualTo(2);
    }

    @Test
    @DisplayName("3. Profanity blocks input before persona + LLM")
    void profanityBlocksInput() {
        llmCaller.setBehavior(r -> { throw new AssertionError("LLM should not be called"); });

        Reply reply = orchestrator.process(userTurn(null, "seu sistema é um lixo", true));

        assertThat(reply.status()).isEqualTo(ReplyStatus.BLOCKED_INPUT);
        assertThat(reply.blockReason()).isEqualTo("profanity");
        assertThat(llmCaller.requests).isEmpty();
    }

    @Test
    @DisplayName("4. Output PII is redacted, not blocked, and conversation continues")
    void outputPiiRedaction() {
        llmCaller.setBehavior(r -> "Confirmado para CPF 111.222.333-44");

        Reply reply = orchestrator.process(userTurn(null, "rastrear pedido", true));

        assertThat(reply.isOk()).isTrue(); // redaction does NOT block
        assertThat(reply.redactionReasons()).containsExactly("pii_detected");
        assertThat(reply.text()).doesNotContain("111.222.333-44").contains("***.***.***-**");

        // Agent message persisted with redacted content
        var lastMsg = repo.listMessages(TENANT, reply.conversationId()).get(1);
        assertThat(lastMsg.content()).doesNotContain("111.222.333-44");
    }

    @Test
    @DisplayName("5. Output profanity blocks the reply")
    void outputProfanityBlocks() {
        llmCaller.setBehavior(r -> "você é um idiota, não vou responder");

        Reply reply = orchestrator.process(userTurn(null, "rastrear pedido", true));

        assertThat(reply.status()).isEqualTo(ReplyStatus.BLOCKED_OUTPUT);
        assertThat(reply.blockReason()).isEqualTo("profanity");
    }

    @Test
    @DisplayName("6. Persona switching across turns in the same conversation")
    void personaSwitching() {
        llmCaller.setBehavior(r -> r.persona().id() + " reply");

        Reply r1 = orchestrator.process(userTurn(null, "rastrear pedido", true));
        assertThat(r1.personaId()).isEqualTo("order_tracking");

        Reply r2 = orchestrator.process(userTurn(r1.conversationId(), "quero abrir uma reclamação", true));
        assertThat(r2.personaId()).isEqualTo("complaint");

        Conversation saved = repo.findById(TENANT, r1.conversationId()).orElseThrow();
        assertThat(saved.persona()).isEqualTo("complaint");
    }

    @Test
    @DisplayName("7. Sticky persona: follow-up with no keyword keeps previous")
    void stickyPersona() {
        llmCaller.setBehavior(r -> r.persona().id() + " reply");

        Reply r1 = orchestrator.process(userTurn(null, "rastrear pedido", true));
        Reply r2 = orchestrator.process(userTurn(r1.conversationId(), "e quando chega?", true));

        assertThat(r2.personaId()).isEqualTo("order_tracking");
    }

    @Test
    @DisplayName("8. Sliding chat memory caps at 6; audit log keeps everything")
    void slidingMemoryVsAudit() {
        llmCaller.setBehavior(r -> "reply " + r.userMessage().length());

        String convId = null;
        for (int i = 1; i <= 10; i++) {
            Reply reply = orchestrator.process(userTurn(convId, "rastrear pedido " + i, true));
            convId = reply.conversationId();
        }

        ChatMemory mem = memoryProvider.getOrCreate(TENANT, convId);
        assertThat(mem.messages()).hasSizeLessThanOrEqualTo(6);

        // Audit: 20 messages total (10 user + 10 agent)
        assertThat(repo.countMessages(TENANT, convId)).isEqualTo(20);
    }

    @Test
    @DisplayName("9. Multi-tenant isolation across the whole pipeline")
    void multiTenantIsolation() {
        // Tenant A
        llmCaller.setBehavior(r -> "A: " + r.tenantId());
        Reply rA = orchestrator.process(userTurn(null, "rastrear pedido", true));

        // Tenant B with its own orchestrator + prompts
        var promptsB = new InMemoryPromptRegistry();
        promptsB.register("tenant-B", "sac.general", "Tenant B prompt");
        Persona bDefault = Persona.of("general", "Geral", "sac.general", List.of());
        var resolverB = new PersonaResolver(List.of(), bDefault);
        var memB = new WindowedChatMemoryProvider(6);

        var llmB = new RecordingLlmCaller();
        llmB.setBehavior(r -> "B: " + r.tenantId());

        var orchestratorB = ConversationOrchestrator.builder()
                .prompts(promptsB)
                .personaResolver(resolverB)
                .memoryProvider(memB)
                .repository(repo) // shared repo, different tenants
                .inputGuardrails(new GuardrailChain(List.of()))
                .outputGuardrails(new GuardrailChain(List.of()))
                .llmCaller(llmB)
                .build();

        Reply rB = orchestratorB.process(new Request(
                "tenant-B", "userB", null, "API", "olá", Map.of(), Map.of()));

        assertThat(rA.conversationId()).isNotEqualTo(rB.conversationId());
        assertThat(repo.listByTenant(TENANT)).extracting(Conversation::id).containsExactly(rA.conversationId());
        assertThat(repo.listByTenant("tenant-B")).extracting(Conversation::id).containsExactly(rB.conversationId());

        // Memories isolated
        assertThat(memoryProvider.size()).isEqualTo(1);
        assertThat(memB.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("10. Prompt versioning: active version drives rendering; rollback works")
    void promptVersioning() {
        prompts.register(TENANT, "sac.tracking", "V2 with {{nome}}");
        prompts.register(TENANT, "sac.tracking", "V3 with {{nome}}");

        // v1 still active
        llmCaller.setBehavior(r -> {
            assertThat(r.promptVersion()).isEqualTo(1);
            assertThat(r.systemPrompt()).contains("Você é um agente SAC");
            return "v1 ok";
        });
        orchestrator.process(userTurn(null, "rastrear pedido", true));

        // Promote v3
        prompts.activateVersion(TENANT, "sac.tracking", 3);
        llmCaller.setBehavior(r -> {
            assertThat(r.promptVersion()).isEqualTo(3);
            assertThat(r.systemPrompt()).contains("V3 with Cliente");
            return "v3 ok";
        });
        orchestrator.process(userTurn(null, "rastrear pedido", true));

        // Rollback to v1
        prompts.activateVersion(TENANT, "sac.tracking", 1);
        llmCaller.setBehavior(r -> {
            assertThat(r.promptVersion()).isEqualTo(1);
            return "rollback ok";
        });
        orchestrator.process(userTurn(null, "rastrear pedido", true));
    }

    @Test
    @DisplayName("11. Custom agent-level guardrail plugs into input chain")
    void customGuardrail() {
        AgentGuardrail offTopic = new AgentGuardrail() {
            @Override public String getName() { return "off-topic"; }
            @Override
            public GuardrailResult evaluateInput(String msg, Map<String, Object> ctx) {
                if (msg.toLowerCase().contains("futebol")) {
                    return GuardrailResult.blocked("off_topic", "Foco em SAC, por favor.");
                }
                return GuardrailResult.ok();
            }
        };

        orchestrator = ConversationOrchestrator.builder()
                .prompts(prompts)
                .personaResolver(personaResolver)
                .memoryProvider(memoryProvider)
                .repository(repo)
                .inputGuardrails(new GuardrailChain(List.of(new ProfanityGuardrail(), offTopic)))
                .outputGuardrails(outputChain)
                .llmCaller(llmCaller)
                .promptVariable("empresa", "Acme")
                .promptVariable("nome", "Cliente")
                .build();

        Reply reply = orchestrator.process(userTurn(null, "quem ganhou o futebol ontem", true));

        assertThat(reply.status()).isEqualTo(ReplyStatus.BLOCKED_INPUT);
        assertThat(reply.blockReason()).isEqualTo("off_topic");
    }

    @Test
    @DisplayName("12. Cross-user conversation access is rejected")
    void rejectsCrossUserConversationAccess() {
        llmCaller.setBehavior(r -> "ok");
        Reply r1 = orchestrator.process(userTurn(null, "rastrear pedido", true));

        // Try to reuse the same conversation with a different userId
        Request req = new Request(TENANT, "otherUser", r1.conversationId(), "API",
                "rastrear pedido", Map.of("identified", true), Map.of());

        assertThatThrownBy(() -> orchestrator.process(req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different user");
    }

    @Test
    @DisplayName("13. Input PII redaction reaches the LLM already masked")
    void inputPiiReachesLlmMasked() {
        llmCaller.setBehavior(r -> {
            assertThat(r.userMessage()).doesNotContain("111.222.333-44");
            assertThat(r.userMessage()).contains("***.***.***-**");
            return "processado";
        });

        var pipeline = ConversationOrchestrator.builder()
                .prompts(prompts)
                .personaResolver(personaResolver)
                .memoryProvider(memoryProvider)
                .repository(repo)
                .inputGuardrails(new GuardrailChain(List.of(new PiiRedactionGuardrail())))
                .outputGuardrails(outputChain)
                .llmCaller(llmCaller)
                .promptVariable("empresa", "Acme")
                .promptVariable("nome", "Cliente")
                .build();

        Reply reply = pipeline.process(userTurn(null, "rastrear pedido CPF 111.222.333-44", true));

        assertThat(reply.isOk()).isTrue();
        assertThat(reply.redactionReasons()).contains("pii_detected");
    }
}
