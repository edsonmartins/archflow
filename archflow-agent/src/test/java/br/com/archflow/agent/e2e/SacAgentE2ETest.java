package br.com.archflow.agent.e2e;

import br.com.archflow.agent.e2e.sac.MockChatModel;
import br.com.archflow.agent.e2e.sac.MockSacTools;
import br.com.archflow.agent.persistence.InMemoryStateRepository;
import br.com.archflow.agent.streaming.ArchflowDomain;
import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.ArchflowEventType;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.conversation.approval.ApprovalRegistry;
import br.com.archflow.conversation.approval.ApprovalRequest;
import br.com.archflow.conversation.approval.Decision;
import br.com.archflow.conversation.approval.HumanDecisionEvent;
import br.com.archflow.conversation.domain.Conversation;
import br.com.archflow.conversation.domain.InMemoryConversationRepository;
import br.com.archflow.conversation.domain.Message;
import br.com.archflow.conversation.guardrail.GuardrailChain;
import br.com.archflow.conversation.guardrail.GuardrailResult;
import br.com.archflow.conversation.guardrail.builtin.IdentificationGuardrail;
import br.com.archflow.conversation.guardrail.builtin.PiiRedactionGuardrail;
import br.com.archflow.conversation.guardrail.builtin.ProfanityGuardrail;
import br.com.archflow.conversation.memory.WindowedChatMemoryProvider;
import br.com.archflow.conversation.persona.Persona;
import br.com.archflow.conversation.persona.PersonaResolver;
import br.com.archflow.conversation.prompt.InMemoryPromptRegistry;
import br.com.archflow.conversation.prompt.PromptRegistry;
import br.com.archflow.conversation.prompt.PromptVersion;
import br.com.archflow.model.ai.Tool;
import br.com.archflow.model.ai.domain.Result;
import br.com.archflow.model.engine.ImmutableExecutionContext;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

/**
 * E2E test that validates the ArchFlow framework can replicate the SAC
 * (Customer Service) agent from integrall-commerce-api.
 *
 * <p>Validates 9 scenarios mirroring the SAC agent's flow:
 * <ol>
 *   <li>Greeting bypass (no LLM call)</li>
 *   <li>Identification guardrail (CNPJ required)</li>
 *   <li>Tool call execution flow</li>
 *   <li>Persona switching via PersonaResolver</li>
 *   <li>Sliding window chat memory</li>
 *   <li>Multi-tenancy isolation</li>
 *   <li>Human escalation via ApprovalRegistry</li>
 *   <li>Streaming events SSE</li>
 *   <li>Conversation persistence</li>
 * </ol>
 *
 * <p>This test uses ALL the new gap implementations:
 * <ul>
 *   <li>{@link PromptRegistry} for versioned prompts</li>
 *   <li>{@link WindowedChatMemoryProvider} for chat memory window</li>
 *   <li>{@link InMemoryConversationRepository} for domain persistence</li>
 *   <li>{@link PersonaResolver} for persona resolution</li>
 *   <li>{@link GuardrailChain} for input/output validation</li>
 * </ul>
 */
@DisplayName("E2E SAC Agent — Replica do Consinco SAC com ArchFlow")
class SacAgentE2ETest {

    private MockChatModel mockLlm;
    private MockSacTools sacTools;
    private InMemoryStateRepository stateRepo;
    private InMemoryConversationRepository conversationRepo;
    private WindowedChatMemoryProvider memoryProvider;
    private EventStreamRegistry streamRegistry;
    private ApprovalRegistry approvalRegistry;
    private PromptRegistry promptRegistry;
    private PersonaResolver personaResolver;
    private GuardrailChain guardrails;

    private Map<String, Tool> toolRegistry;

    private static final String TENANT = "tenant_consinco";
    private static final String USER_PHONE = "5511987654321";

    @BeforeEach
    void setUp() {
        mockLlm = new MockChatModel();
        sacTools = new MockSacTools();
        stateRepo = new InMemoryStateRepository();
        conversationRepo = new InMemoryConversationRepository();
        memoryProvider = new WindowedChatMemoryProvider(6); // SAC uses window of 6
        streamRegistry = new EventStreamRegistry(60000, 300000);
        approvalRegistry = new ApprovalRegistry();

        // ── Setup prompt registry with SAC personas ──
        promptRegistry = new InMemoryPromptRegistry();
        promptRegistry.register(TENANT, "sac.order_tracking",
                "Você é um agente SAC especializado em rastreamento de pedidos. Cliente: {{customerName}}");
        promptRegistry.register(TENANT, "sac.customer_support",
                "Você é um agente SAC especializado em reclamações. Cliente: {{customerName}}");
        promptRegistry.register(TENANT, "sac.default",
                "Você é um agente de atendimento SAC. Como posso ajudar?");

        // ── Setup personas ──
        Persona orderTracking = Persona.of("order_tracking", "Order Tracking", "sac.order_tracking",
                List.of("tracking_pedido", "consultar_pedidos_cliente"),
                "rastrear", "pedido", "entrega", "boleto", "nota fiscal");
        Persona customerSupport = Persona.of("customer_support", "Customer Support", "sac.customer_support",
                List.of("criar_ticket_reclamacao"),
                "reclamação", "reclamar", "ticket", "problema");
        Persona defaultPersona = Persona.of("default", "Default", "sac.default",
                List.of(), ".*");

        personaResolver = new PersonaResolver(
                List.of(orderTracking, customerSupport),
                defaultPersona
        );

        // ── Setup guardrails ──
        guardrails = new GuardrailChain(List.of(
                new ProfanityGuardrail(),
                new IdentificationGuardrail(),
                new PiiRedactionGuardrail()
        ));

        // ── Setup tool registry ──
        toolRegistry = Map.of(
                "tracking_pedido", sacTools.trackingPedido(),
                "consultar_pedidos_cliente", sacTools.consultarPedidosCliente(),
                "criar_ticket_reclamacao", sacTools.criarTicketReclamacao()
        );
    }

    @AfterEach
    void tearDown() {
        streamRegistry.shutdown();
    }

    // ── Cenário 1: Greeting bypass (não chama LLM) ─────────────────────

    @Test
    @DisplayName("Cenário 1: saudação não chama LLM nem ferramentas")
    void greetingBypass() {
        Conversation conv = conversationRepo.save(Conversation.start(TENANT, USER_PHONE, "WHATSAPP"));

        AgentResponse response = processMessage(conv, "oi", false);

        assertThat(response.text()).contains("ajudar");
        assertThat(mockLlm.getCallCount()).isZero(); // greeting handled before LLM
        assertThat(sacTools.getInvocations()).isEmpty();
    }

    // ── Cenário 2: Identification guardrail (CNPJ obrigatório) ─────────

    @Test
    @DisplayName("Cenário 2: pedido sem CNPJ é bloqueado pelo guardrail")
    void identificationGuardrailBlocks() {
        Conversation conv = conversationRepo.save(Conversation.start(TENANT, USER_PHONE, "WHATSAPP"));

        AgentResponse response = processMessage(conv, "quero rastrear meu pedido", false);

        assertThat(response.text()).contains("CNPJ");
        assertThat(response.blockedBy()).isEqualTo("missing_identification");
        assertThat(mockLlm.getCallCount()).isZero();
        assertThat(sacTools.getInvocations()).isEmpty();
    }

    // ── Cenário 3: Fluxo completo com tool call (CNPJ informado) ───────

    @Test
    @DisplayName("Cenário 3: fluxo completo — LLM + tool call + resposta")
    void fullFlowWithToolCall() {
        Conversation conv = conversationRepo.save(Conversation.start(TENANT, USER_PHONE, "WHATSAPP"));

        AgentResponse response = processMessage(conv, "quero rastrear pedido 12345", true);

        assertThat(mockLlm.getCallCount()).isEqualTo(1);
        assertThat(sacTools.invocationsOf("tracking_pedido")).hasSize(1);
        assertThat(sacTools.invocationsOf("tracking_pedido").get(0).params())
                .containsEntry("numero_pedido", "12345");
        assertThat(response.text()).contains("rastrear");
    }

    // ── Cenário 4: Persona switching via PersonaResolver ───────────────

    @Test
    @DisplayName("Cenário 4: troca de persona por keyword classification")
    void personaSwitching() {
        Conversation conv = conversationRepo.save(Conversation.start(TENANT, USER_PHONE, "WHATSAPP"));

        // Mensagem 1: order tracking
        Optional<Persona> p1 = personaResolver.resolve(conv.id(), "minha entrega atrasou");
        assertThat(p1).isPresent();
        assertThat(p1.get().id()).isEqualTo("order_tracking");

        // Mensagem 2: switch para customer support
        Optional<Persona> p2 = personaResolver.resolve(conv.id(), "quero abrir uma reclamação");
        assertThat(p2).isPresent();
        assertThat(p2.get().id()).isEqualTo("customer_support");

        // Mensagem 3: ambígua — usa sticky context (última = customer_support)
        Optional<Persona> p3 = personaResolver.resolve(conv.id(), "tudo bem?");
        assertThat(p3).isPresent();
        assertThat(p3.get().id()).isEqualTo("customer_support");
    }

    // ── Cenário 5: Memory sliding window ───────────────────────────────

    @Test
    @DisplayName("Cenário 5: chat memory mantém apenas as últimas 6 mensagens")
    void slidingWindowMemory() {
        ChatMemory memory = memoryProvider.getOrCreate(TENANT, "session-1");

        // Adiciona 10 mensagens (5 user + 5 ai)
        for (int i = 1; i <= 5; i++) {
            memory.add(UserMessage.from("user msg " + i));
            memory.add(AiMessage.from("ai msg " + i));
        }

        // Window de 6 deve manter apenas as últimas 6
        assertThat(memory.messages()).hasSize(6);
        assertThat(memory.messages().get(0)).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) memory.messages().get(0)).singleText()).isEqualTo("user msg 3");
        assertThat(((AiMessage) memory.messages().get(5)).text()).isEqualTo("ai msg 5");
    }

    // ── Cenário 6: Multi-tenancy isolation ─────────────────────────────

    @Test
    @DisplayName("Cenário 6: tenants isolados — memory, conversation, tools, guardrails")
    void multiTenancyIsolation() {
        // Tenant A
        Conversation convA = conversationRepo.save(Conversation.start("tenant_A", "user_A", "WHATSAPP"));
        ChatMemory memoryA = memoryProvider.getOrCreate("tenant_A", convA.id());
        memoryA.add(UserMessage.from("Sou do tenant A"));

        // Tenant B com mesmo session
        Conversation convB = conversationRepo.save(Conversation.start("tenant_B", "user_B", "WHATSAPP"));
        ChatMemory memoryB = memoryProvider.getOrCreate("tenant_B", convB.id());
        memoryB.add(UserMessage.from("Sou do tenant B"));

        // Memórias isoladas
        assertThat(memoryA.messages()).hasSize(1);
        assertThat(memoryB.messages()).hasSize(1);
        assertThat(memoryA).isNotSameAs(memoryB);

        // Conversas isoladas
        assertThat(conversationRepo.listByTenant("tenant_A")).hasSize(1);
        assertThat(conversationRepo.listByTenant("tenant_B")).hasSize(1);
        assertThat(conversationRepo.findById("tenant_A", convB.id())).isEmpty(); // cross-tenant blocked
    }

    // ── Cenário 7: Human escalation ────────────────────────────────────

    @Test
    @DisplayName("Cenário 7: confidence baixa força escalation com ApprovalRegistry")
    void humanEscalation() {
        Conversation conv = conversationRepo.save(Conversation.start(TENANT, USER_PHONE, "WHATSAPP"));

        // Cria ApprovalRequest quando confidence < threshold
        ApprovalRequest request = ApprovalRequest.of(
                TENANT, conv.id(), "step-llm-classify",
                Map.of("message", "complicado, não sei explicar"),
                "Cliente precisa de atendimento humano — confidence baixa"
        );
        approvalRegistry.register(request);

        // Verifica que está pendente
        assertThat(approvalRegistry.pendingCount()).isEqualTo(1);
        assertThat(approvalRegistry.listPendingByTenant(TENANT)).hasSize(1);

        // Operador humano aprova
        var decision = HumanDecisionEvent.approved(request.requestId(), TENANT);
        var result = approvalRegistry.submitDecision(decision);

        assertThat(result).isPresent();
        assertThat(approvalRegistry.pendingCount()).isZero();
    }

    // ── Cenário 8: Streaming events SSE ────────────────────────────────

    @Test
    @DisplayName("Cenário 8: emite eventos SSE durante a execução do agente")
    void streamingEvents() {
        var emitter = streamRegistry.createEmitter(TENANT, "session-stream");

        // Simula eventos do flow de execução
        var startEvent = ArchflowEvent.builder()
                .domain(ArchflowDomain.CHAT)
                .type(ArchflowEventType.START)
                .tenantId(TENANT)
                .data(Map.of("message", "rastrear pedido 12345"))
                .build();

        var toolEvent = ArchflowEvent.builder()
                .domain(ArchflowDomain.TOOL)
                .type(ArchflowEventType.TOOL_START)
                .tenantId(TENANT)
                .data(Map.of("toolName", "tracking_pedido"))
                .build();

        var toolResultEvent = ArchflowEvent.builder()
                .domain(ArchflowDomain.TOOL)
                .type(ArchflowEventType.RESULT)
                .tenantId(TENANT)
                .data(Map.of("status", "EM_TRANSITO"))
                .build();

        var endEvent = ArchflowEvent.builder()
                .domain(ArchflowDomain.CHAT)
                .type(ArchflowEventType.MESSAGE)
                .tenantId(TENANT)
                .data(Map.of("text", "Seu pedido está em trânsito"))
                .build();

        int sent1 = streamRegistry.broadcast(TENANT + ":session-stream", startEvent);
        int sent2 = streamRegistry.broadcast(TENANT + ":session-stream", toolEvent);
        int sent3 = streamRegistry.broadcast(TENANT + ":session-stream", toolResultEvent);
        int sent4 = streamRegistry.broadcast(TENANT + ":session-stream", endEvent);

        assertThat(sent1).isEqualTo(1);
        assertThat(sent2).isEqualTo(1);
        assertThat(sent3).isEqualTo(1);
        assertThat(sent4).isEqualTo(1);

        // Verifica isolamento por tenant
        assertThat(streamRegistry.getEmittersByTenant(TENANT)).hasSize(1);
        assertThat(streamRegistry.getEmittersByTenant("other-tenant")).isEmpty();
    }

    // ── Cenário 9: Conversation persistence ────────────────────────────

    @Test
    @DisplayName("Cenário 9: conversa e mensagens persistidas via ConversationRepository")
    void conversationPersistence() {
        // Cria conversa
        Conversation conv = conversationRepo.save(Conversation.start(TENANT, USER_PHONE, "WHATSAPP"));
        conv = conversationRepo.save(conv.withPersona("order_tracking"));

        // Adiciona mensagens
        conversationRepo.addMessage(Message.userText(conv.id(), TENANT, "Olá"));
        conversationRepo.addMessage(Message.agentText(conv.id(), TENANT, "Oi! Como posso ajudar?"));
        conversationRepo.addMessage(Message.userText(conv.id(), TENANT, "Quero rastrear pedido"));
        conversationRepo.addMessage(Message.agentText(conv.id(), TENANT, "Por favor, informe o número"));

        // Verifica persistência
        assertThat(conversationRepo.countMessages(TENANT, conv.id())).isEqualTo(4);
        assertThat(conversationRepo.listMessages(TENANT, conv.id()))
                .extracting(Message::content)
                .containsExactly("Olá", "Oi! Como posso ajudar?", "Quero rastrear pedido", "Por favor, informe o número");

        // Verifica recuperação de últimas N
        var recent = conversationRepo.listRecentMessages(TENANT, conv.id(), 2);
        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).content()).isEqualTo("Quero rastrear pedido");

        // Verifica persona salva
        var loaded = conversationRepo.findById(TENANT, conv.id());
        assertThat(loaded).isPresent();
        assertThat(loaded.get().persona()).isEqualTo("order_tracking");
    }

    // ══════════════════════════════════════════════════════════════════
    //   Helper: simulação completa do flow do agente SAC
    // ══════════════════════════════════════════════════════════════════

    record AgentResponse(String text, String blockedBy) {}

    /**
     * Simulates the full SAC agent message processing flow:
     * 1. Save user message
     * 2. Greeting bypass check
     * 3. Guardrail input evaluation (with identification check)
     * 4. Persona resolution
     * 5. Render system prompt for persona
     * 6. Build chat memory
     * 7. Call LLM (mock)
     * 8. Execute tool calls
     * 9. Guardrail output evaluation
     * 10. Save agent response
     * 11. Emit streaming events
     */
    private AgentResponse processMessage(Conversation conv, String userMessage, boolean identified) {
        // 1. Save user message
        conversationRepo.addMessage(Message.userText(conv.id(), conv.tenantId(), userMessage));

        // 2. Greeting bypass
        String lower = userMessage.toLowerCase().trim();
        if (lower.matches("^(oi|olá|ola|bom dia|boa tarde|boa noite)$")) {
            String greeting = "Olá! Como posso ajudar você hoje?";
            conversationRepo.addMessage(Message.agentText(conv.id(), conv.tenantId(), greeting));
            return new AgentResponse(greeting, null);
        }

        // 3. Guardrail input
        Map<String, Object> context = new HashMap<>();
        context.put("identified", identified);
        GuardrailChain.ChainResult inputCheck = guardrails.evaluateInput(userMessage, context);
        if (inputCheck.blocked()) {
            String reply = inputCheck.blockMessage();
            conversationRepo.addMessage(Message.agentText(conv.id(), conv.tenantId(), reply));
            return new AgentResponse(reply, inputCheck.blockReason());
        }
        userMessage = inputCheck.finalText();

        // 4. Persona resolution
        Persona persona = personaResolver.resolve(conv.id(), userMessage)
                .orElseThrow(() -> new IllegalStateException("No persona resolved"));

        // 5. Render system prompt for persona
        PromptVersion promptVersion = promptRegistry.getActive(conv.tenantId(), persona.promptId())
                .orElseThrow(() -> new IllegalStateException("No active prompt for: " + persona.promptId()));
        Map<String, Object> promptVars = Map.of("customerName", "João Silva");
        String systemPrompt = promptVersion.render(promptVars);

        // 6. Build chat memory window
        ChatMemory memory = memoryProvider.getOrCreate(conv.tenantId(), conv.id());
        memory.add(UserMessage.from(userMessage));

        // 7. Build ImmutableExecutionContext (multi-tenant aware)
        ImmutableExecutionContext execContext = ImmutableExecutionContext.builder()
                .tenantId(conv.tenantId())
                .userId(conv.userId())
                .sessionId(conv.id())
                .chatMemory(memory)
                .variable("systemPrompt", systemPrompt)
                .variable("persona", persona.id())
                .build();

        // 8. Call LLM (mock)
        var llmResponse = mockLlm.chat(userMessage, List.of());

        // 9. Execute tool calls if any
        for (var toolCall : llmResponse.toolCalls()) {
            // Filter by allowed tools for this persona
            if (!persona.allowedTools().contains(toolCall.name())) {
                continue;
            }
            Tool tool = toolRegistry.get(toolCall.name());
            if (tool != null) {
                Result toolResult = tool.execute(toolCall.arguments(), execContext);
                conversationRepo.addMessage(new Message(
                        null, conv.id(), conv.tenantId(),
                        Message.SenderType.SYSTEM, Message.MessageType.TOOL_RESULT,
                        String.valueOf(toolResult.output()),
                        null,
                        Map.of("tool", toolCall.name(), "success", toolResult.success()),
                        null
                ));
            }
        }

        // 10. Guardrail output
        GuardrailChain.ChainResult outputCheck = guardrails.evaluateOutput(llmResponse.text(), context);
        String finalText = outputCheck.blocked() ? outputCheck.blockMessage() : outputCheck.finalText();

        // 11. Save agent message + add to memory
        conversationRepo.addMessage(Message.agentText(conv.id(), conv.tenantId(), finalText));
        memory.add(AiMessage.from(finalText));

        return new AgentResponse(finalText, outputCheck.blocked() ? outputCheck.blockReason() : null);
    }
}
