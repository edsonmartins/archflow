package br.com.archflow.agent.e2e;

import br.com.archflow.agent.persistence.InMemoryStateRepository;
import br.com.archflow.agent.queue.AgentInvocationQueue;
import br.com.archflow.agent.queue.InMemoryAgentInvocationQueue;
import br.com.archflow.agent.queue.InvocationRequest;
import br.com.archflow.agent.streaming.ArchflowDomain;
import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.ArchflowEventType;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.conversation.ConversationManager;
import br.com.archflow.conversation.approval.ApprovalRegistry;
import br.com.archflow.conversation.approval.ApprovalRequest;
import br.com.archflow.conversation.approval.Decision;
import br.com.archflow.conversation.approval.HumanDecisionEvent;
import br.com.archflow.conversation.approval.LearningCallback;
import br.com.archflow.conversation.memory.Episode;
import br.com.archflow.conversation.memory.InMemoryEpisodicMemory;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ImmutableExecutionContext;
import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.flow.FlowStatus;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

@DisplayName("E2E Multi-Tenant Integration")
class MultiTenantE2ETest {

    private InMemoryStateRepository stateRepo;
    private InMemoryEpisodicMemory episodicMemory;
    private EventStreamRegistry streamRegistry;
    private InMemoryAgentInvocationQueue invocationQueue;
    private ApprovalRegistry approvalRegistry;

    @BeforeEach
    void setUp() {
        stateRepo = new InMemoryStateRepository();
        episodicMemory = new InMemoryEpisodicMemory();
        streamRegistry = new EventStreamRegistry(60000, 300000);
        invocationQueue = new InMemoryAgentInvocationQueue(5, 100);
        ConversationManager.reset();
    }

    @AfterEach
    void tearDown() {
        streamRegistry.shutdown();
    }

    // ── Cenário 1: Isolamento completo entre tenants ───────────────────

    @Test
    @DisplayName("E2E: Two tenants should have completely isolated state, memory and events")
    void twoTenantsShouldBeCompletelyIsolated() {
        // --- Tenant A: flow de vendas ---
        var ctxA = ImmutableExecutionContext.builder()
                .tenantId("vendax-distribuidora-1")
                .userId("vendedor-joao")
                .sessionId("session-A1")
                .chatMemory(MessageWindowChatMemory.builder().maxMessages(10).build())
                .variable("playbookConfig", "config-distribuidora-1")
                .build();

        var stateA = FlowState.builder()
                .tenantId("vendax-distribuidora-1")
                .flowId("flow-vendas-1")
                .status(FlowStatus.RUNNING)
                .variables(new HashMap<>(Map.of("tipo", "venda")))
                .build();

        // --- Tenant B: flow jurídico ---
        var ctxB = ImmutableExecutionContext.builder()
                .tenantId("juridico-escritorio-1")
                .userId("advogado-maria")
                .sessionId("session-B1")
                .chatMemory(MessageWindowChatMemory.builder().maxMessages(10).build())
                .variable("caseConfig", "config-escritorio-1")
                .build();

        var stateB = FlowState.builder()
                .tenantId("juridico-escritorio-1")
                .flowId("flow-vendas-1") // SAME flowId — must not collide
                .status(FlowStatus.PAUSED)
                .variables(new HashMap<>(Map.of("tipo", "processo")))
                .build();

        // Salvar estados
        stateRepo.saveState("vendax-distribuidora-1", "flow-vendas-1", stateA);
        stateRepo.saveState("juridico-escritorio-1", "flow-vendas-1", stateB);

        // Verificar isolamento
        var retrievedA = stateRepo.getState("vendax-distribuidora-1", "flow-vendas-1");
        var retrievedB = stateRepo.getState("juridico-escritorio-1", "flow-vendas-1");

        assertThat(retrievedA.getStatus()).isEqualTo(FlowStatus.RUNNING);
        assertThat(retrievedA.getVariables()).containsEntry("tipo", "venda");

        assertThat(retrievedB.getStatus()).isEqualTo(FlowStatus.PAUSED);
        assertThat(retrievedB.getVariables()).containsEntry("tipo", "processo");

        // Salvar episódios
        episodicMemory.store("vendax-distribuidora-1",
                Episode.of("vendax-distribuidora-1", "session-A1", "Cliente pediu desconto de 10%",
                        Episode.EpisodeType.INTERACTION, 0.8));
        episodicMemory.store("juridico-escritorio-1",
                Episode.of("juridico-escritorio-1", "session-B1", "Prazo do recurso é amanhã",
                        Episode.EpisodeType.ACTION, 0.9));

        // Recall deve ser isolado
        var recallA = episodicMemory.recall("vendax-distribuidora-1", "desconto", "session-A1", 10);
        var recallB = episodicMemory.recall("juridico-escritorio-1", "prazo", "session-B1", 10);

        assertThat(recallA).hasSize(1);
        assertThat(recallA.get(0).episode().content()).contains("desconto");

        assertThat(recallB).hasSize(1);
        assertThat(recallB.get(0).episode().content()).contains("recurso");

        // Cross-tenant recall deve retornar vazio
        var crossRecall = episodicMemory.recall("vendax-distribuidora-1", "recurso", "session-B1", 10);
        assertThat(crossRecall).isEmpty();

        // Streaming isolado
        streamRegistry.createEmitter("vendax-distribuidora-1", "session-A1");
        streamRegistry.createEmitter("juridico-escritorio-1", "session-B1");

        assertThat(streamRegistry.getEmittersByTenant("vendax-distribuidora-1")).hasSize(1);
        assertThat(streamRegistry.getEmittersByTenant("juridico-escritorio-1")).hasSize(1);

        // Listar estados por tenant
        assertThat(stateRepo.getStatesByTenant("vendax-distribuidora-1")).hasSize(1);
        assertThat(stateRepo.getStatesByTenant("juridico-escritorio-1")).hasSize(1);

        // ImmutableExecutionContext fields
        assertThat(ctxA.getTenantId()).isEqualTo("vendax-distribuidora-1");
        assertThat(ctxA.get("playbookConfig")).contains("config-distribuidora-1");
        assertThat(ctxB.getTenantId()).isEqualTo("juridico-escritorio-1");
        assertThat(ctxB.get("caseConfig")).contains("config-escritorio-1");
    }

    // ── Cenário 2: Human-in-the-Loop completo ──────────────────────────

    @Test
    @DisplayName("E2E: Approval workflow — request → approve → resume")
    void approvalWorkflowHappyPath() {
        List<String> learningEvents = new CopyOnWriteArrayList<>();
        LearningCallback callback = new LearningCallback() {
            @Override
            public void onRejected(String tenantId, String requestId, Object proposal) {
                learningEvents.add("REJECTED:" + tenantId + ":" + requestId);
            }
            @Override
            public void onEdited(String tenantId, String requestId, Object original, Object edited) {
                learningEvents.add("EDITED:" + tenantId + ":" + requestId);
            }
        };
        approvalRegistry = new ApprovalRegistry(callback);

        // 1. Agente gera proposta e solicita aprovação
        var proposal = Map.of("type", "QUOTE", "value", 15000, "discount", "10%");
        var request = ApprovalRequest.of("tenant-1", "flow-vendas", "step-quote",
                proposal, "Aprovar cotação de R$15.000 com 10% de desconto");

        approvalRegistry.register(request);

        // 2. Listar pendentes do tenant
        var pending = approvalRegistry.listPendingByTenant("tenant-1");
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).proposal()).isEqualTo(proposal);

        // 3. Humano aprova
        var decision = HumanDecisionEvent.approved(request.requestId(), "tenant-1");
        var result = approvalRegistry.submitDecision(decision);

        assertThat(result).isPresent();
        assertThat(result.get().flowId()).isEqualTo("flow-vendas");
        assertThat(approvalRegistry.pendingCount()).isZero();

        // Não deve gerar learning event para APPROVED
        assertThat(learningEvents).isEmpty();
    }

    @Test
    @DisplayName("E2E: Approval workflow — request → reject → learning callback")
    void approvalWorkflowReject() {
        List<String> learningEvents = new CopyOnWriteArrayList<>();
        approvalRegistry = new ApprovalRegistry(new LearningCallback() {
            @Override
            public void onRejected(String tenantId, String requestId, Object proposal) {
                learningEvents.add("REJECTED:" + tenantId);
            }
            @Override
            public void onEdited(String tenantId, String requestId, Object original, Object edited) {}
        });

        var request = ApprovalRequest.of("tenant-1", "flow-1", "step-1",
                Map.of("suggestion", "bad idea"), "Approve this?");
        approvalRegistry.register(request);

        approvalRegistry.submitDecision(HumanDecisionEvent.rejected(request.requestId(), "tenant-1"));

        assertThat(learningEvents).containsExactly("REJECTED:tenant-1");
    }

    @Test
    @DisplayName("E2E: Approval workflow — timeout → auto-reject")
    void approvalWorkflowTimeout() throws InterruptedException {
        List<HumanDecisionEvent> timeoutEvents = new CopyOnWriteArrayList<>();
        approvalRegistry = new ApprovalRegistry(null, Decision.REJECTED, timeoutEvents::add);

        var request = new ApprovalRequest(null, "tenant-1", "flow-1", "step-1",
                "proposal", "desc", Duration.ofMillis(1), null);
        approvalRegistry.register(request);

        Thread.sleep(10);
        approvalRegistry.processExpired();

        assertThat(timeoutEvents).hasSize(1);
        assertThat(timeoutEvents.get(0).decision()).isEqualTo(Decision.REJECTED);
        assertThat(timeoutEvents.get(0).responderId()).isEqualTo("SYSTEM_TIMEOUT");
        assertThat(approvalRegistry.pendingCount()).isZero();
    }

    // ── Cenário 3: Invocation queue com controle de recursão ───────────

    @Test
    @DisplayName("E2E: Agent invocation chain with recursion control")
    void invocationChainWithRecursionControl() {
        // Simular cadeia: Nexus → Coach → Briefing → Churn (depth 3)
        var root = InvocationRequest.root("tenant-1", "nexus-orchestrator", Map.of("action", "weekly-review"));
        invocationQueue.submit(root);

        var polled = invocationQueue.poll();
        assertThat(polled).isPresent();

        var child1 = polled.get().childInvocation("coach-agent", Map.of("task", "evaluate-vendor"));
        invocationQueue.submit(child1);

        var polled2 = invocationQueue.poll();
        var child2 = polled2.get().childInvocation("briefing-agent", Map.of("task", "generate-briefing"));
        invocationQueue.submit(child2);

        var polled3 = invocationQueue.poll();
        var child3 = polled3.get().childInvocation("churn-agent", Map.of("task", "check-churn"));
        invocationQueue.submit(child3);

        // Depth 4 should still work (max is 5)
        var polled4 = invocationQueue.poll();
        var child4 = polled4.get().childInvocation("alert-agent", Map.of("task", "send-alert"));
        invocationQueue.submit(child4);

        // Depth 5 = max, should work
        var polled5 = invocationQueue.poll();
        var child5 = polled5.get().childInvocation("log-agent", Map.of("task", "log"));
        invocationQueue.submit(child5);

        // Depth 6 > max 5, should throw
        var polled6 = invocationQueue.poll();
        var child6 = polled6.get().childInvocation("overflow-agent", Map.of());
        assertThatThrownBy(() -> invocationQueue.submit(child6))
                .isInstanceOf(AgentInvocationQueue.MaxRecursionDepthException.class);
    }

    // ── Cenário 4: ImmutableExecutionContext workflow ───────────────────

    @Test
    @DisplayName("E2E: ImmutableExecutionContext — build → withVariable → withState → snapshot")
    void immutableContextWorkflow() {
        var ctx = ImmutableExecutionContext.builder()
                .tenantId("tenant-1")
                .userId("user-1")
                .sessionId("session-1")
                .chatMemory(MessageWindowChatMemory.builder().maxMessages(50).build())
                .variable("playbookConfig", Map.of("mode", "aggressive"))
                .build();

        // Step 1: Agent adds variables (immutably)
        var step1Ctx = (ImmutableExecutionContext) ctx.withVariable("step1.output", "Customer interested in product X");
        assertThat(ctx.get("step1.output")).isEmpty(); // original unchanged
        assertThat(step1Ctx.get("step1.output")).contains("Customer interested in product X");

        // Step 2: Engine sets state
        var state = FlowState.builder()
                .tenantId("tenant-1")
                .flowId("flow-1")
                .status(FlowStatus.RUNNING)
                .currentStepId("step-2")
                .build();
        var step2Ctx = step1Ctx.withState(state);
        assertThat(step2Ctx.getState().getCurrentStepId()).isEqualTo("step-2");
        assertThat(step1Ctx.getState()).isNull(); // previous unchanged

        // Step 3: Snapshot for parallel execution
        var snapshot = step2Ctx.snapshot();
        assertThat(snapshot).isSameAs(step2Ctx); // already immutable

        // Verify all fields preserved through chain
        assertThat(step2Ctx.getTenantId()).isEqualTo("tenant-1");
        assertThat(step2Ctx.getUserId()).isEqualTo("user-1");
        assertThat(step2Ctx.getSessionId()).isEqualTo("session-1");
        assertThat(step2Ctx.get("playbookConfig")).isPresent();
    }

    // ── Cenário 5: PAYLOAD streaming por tenant ────────────────────────

    @Test
    @DisplayName("E2E: Rich payload streaming isolated by tenant")
    void richPayloadStreamingIsolatedByTenant() {
        // Tenant A: vendas
        var emitterA = streamRegistry.createEmitter("tenant-A", "session-A1");
        // Tenant B: jurídico
        var emitterB = streamRegistry.createEmitter("tenant-B", "session-B1");

        // Broadcast payload rico para tenant A
        var payloadEvent = ArchflowEvent.builder()
                .domain(ArchflowDomain.PAYLOAD)
                .type(ArchflowEventType.PAYLOAD_COMPLETE)
                .tenantId("tenant-A")
                .data(Map.of("type", "AI_SUGGESTION", "body", "Ofereça desconto de 5%"))
                .build();

        int sentA = streamRegistry.broadcast("tenant-A:session-A1", payloadEvent);
        assertThat(sentA).isEqualTo(1);

        // Tenant B não recebeu (broadcast é por executionId, não global)
        int sentB = streamRegistry.broadcast("tenant-B:session-B1", payloadEvent);
        assertThat(sentB).isEqualTo(1); // Sent but it's a different channel

        // Verificar isolamento
        assertThat(streamRegistry.getEmittersByTenant("tenant-A")).hasSize(1);
        assertThat(streamRegistry.getEmittersByTenant("tenant-B")).hasSize(1);
        assertThat(streamRegistry.getEmittersByTenant("tenant-C")).isEmpty();
    }

    // ── Cenário 6: Backward compatibility (SYSTEM tenant) ──────────────

    @Test
    @DisplayName("E2E: Legacy code without tenantId should work with SYSTEM default")
    @SuppressWarnings("deprecation")
    void legacyCodeShouldWorkWithSystemDefault() {
        // Legacy ExecutionContext (mutable)
        var legacyCtx = new DefaultExecutionContext(
                MessageWindowChatMemory.builder().maxMessages(10).build());

        assertThat(legacyCtx.getTenantId()).isEqualTo("SYSTEM");

        // Legacy FlowState without tenantId
        var legacyState = FlowState.builder()
                .flowId("legacy-flow")
                .status(FlowStatus.RUNNING)
                .variables(new HashMap<>())
                .build();

        // Should save under SYSTEM tenant
        stateRepo.saveState("legacy-flow", legacyState);

        // Legacy episodic memory
        episodicMemory.store(Episode.of("ctx-1", "legacy data", 0.5));
        var results = episodicMemory.getByContext("ctx-1"); // uses SYSTEM
        assertThat(results).hasSize(1);
    }
}
