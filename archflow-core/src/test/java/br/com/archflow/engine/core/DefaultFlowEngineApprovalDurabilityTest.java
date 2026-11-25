package br.com.archflow.engine.core;

import br.com.archflow.engine.exceptions.FlowEngineException;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.engine.validation.FlowValidator;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.engine.ExecutionKeys;
import br.com.archflow.model.engine.ExecutionMetrics;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.error.ExecutionError;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowMetadata;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.flow.FlowStatus;
import br.com.archflow.model.flow.StateUpdate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Caracteriza o gate de aprovação humana do {@link DefaultFlowEngine} usando a
 * semântica <em>real</em> da suspensão cooperativa.
 *
 * <p>Detalhe metodológico que importa: quando {@code requestApproval} levanta o
 * flag de pause, {@code DefaultFlowExecutor} devolve
 * {@code Traversal(results, PAUSED)} — a travessia <b>desenrola</b>, não bloqueia
 * (ver {@code DefaultFlowExecutor:197-199} e o comentário em
 * {@code DefaultFlowEngine.persistTerminalState:629-635}). Portanto o mock de
 * {@code executeFlow} aqui <b>retorna</b> um resultado PAUSED em vez de travar
 * numa latch. Um mock bloqueante mantém a entrada viva em {@code activeExecutions}
 * artificialmente e esconde exatamente o defeito abaixo.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DefaultFlowEngine — durabilidade do gate de aprovação")
class DefaultFlowEngineApprovalDurabilityTest {

    /** Chave privada do motor onde o requestId é guardado nas variáveis do estado. */
    private static final String APPROVAL_REQUEST_KEY = "__archflow.approvalRequestId";

    @Mock ExecutionManager executionManager;
    @Mock FlowRepository flowRepository;
    @Mock FlowValidator flowValidator;

    /** Store durável: sobrevive à troca de instância do motor (o "restart"). */
    private final StateManager durableStore = new StateManager() {
        private final Map<String, FlowState> states = new ConcurrentHashMap<>();

        @Override
        public void saveState(String flowId, FlowState state) {
            states.put(flowId, state);
        }

        @Override
        public FlowState loadState(String flowId) {
            return states.get(flowId);
        }

        @Override
        public void updateState(String flowId, StateUpdate update) {
            // não usado por estes testes
        }
    };

    private DefaultFlowEngine newEngine(int maxConcurrentFlows) {
        return new DefaultFlowEngine(
                executionManager, flowRepository, durableStore, flowValidator,
                null, null, maxConcurrentFlows, 30_000);
    }

    private Flow mockFlow(String id) {
        Flow flow = mock(Flow.class, withSettings().lenient());
        when(flow.getId()).thenReturn(id);
        when(flow.getSteps()).thenReturn(List.of());
        when(flow.getMetadata()).thenReturn(
                FlowMetadata.builder().name("Test").version("1.0").build());
        return flow;
    }

    private FlowResult resultWith(ExecutionStatus status) {
        return new FlowResult() {
            @Override public ExecutionStatus getStatus() { return status; }
            @Override public Optional<Object> getOutput() { return Optional.empty(); }
            @Override public ExecutionMetrics getMetrics() { return null; }
            @Override public List<ExecutionError> getErrors() { return List.of(); }
        };
    }

    /**
     * Reproduz a sequência de produção: um step pede aprovação, o executor
     * percebe o pause no próximo limite de step e devolve PAUSED.
     *
     * @return o requestId emitido
     */
    private String runUntilAwaitingApproval(DefaultFlowEngine engine, Flow flow) throws Exception {
        AtomicReference<String> requestId = new AtomicReference<>();
        when(executionManager.executeFlow(eq(flow), any(ExecutionContext.class)))
                .thenAnswer(inv -> {
                    requestId.set(engine.requestApproval(flow.getId(), "step-2", "proposta"));
                    return resultWith(ExecutionStatus.PAUSED);
                });

        FlowResult result = engine.startFlow(flow.getId(), Map.of()).get(5, TimeUnit.SECONDS);
        assertThat(result.getStatus())
                .as("suspensão cooperativa devolve PAUSED")
                .isEqualTo(ExecutionStatus.PAUSED);
        return requestId.get();
    }

    @Nested
    @DisplayName("estado suspenso")
    class SuspendedState {

        @Test
        @DisplayName("AWAITING_APPROVAL e requestId são persistidos (pré-condição)")
        void awaitingApprovalIsPersisted() throws Exception {
            Flow flow = mockFlow("flow-1");
            when(flowRepository.findById("flow-1")).thenReturn(Optional.of(flow));

            String requestId = runUntilAwaitingApproval(newEngine(2), flow);

            FlowState persisted = durableStore.loadState("flow-1");
            assertThat(persisted).isNotNull();
            assertThat(persisted.getStatus()).isEqualTo(FlowStatus.AWAITING_APPROVAL);
            assertThat(persisted.getVariables()).containsEntry(APPROVAL_REQUEST_KEY, requestId);
        }

        @Test
        @DisplayName("o permit do semáforo é devolvido enquanto se espera o humano")
        void permitIsReleasedWhileAwaiting() throws Exception {
            Flow flow = mockFlow("flow-1");
            when(flowRepository.findById("flow-1")).thenReturn(Optional.of(flow));

            DefaultFlowEngine engine = newEngine(2);
            runUntilAwaitingApproval(engine, flow);

            assertThat(engine.getAvailablePermits())
                    .as("espera humana não é trabalho em execução — não deve reter slot")
                    .isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("submissão da aprovação")
    class SubmitApproval {

        @Test
        @DisplayName("aprovação pode ser submetida no mesmo processo, após a suspensão")
        void approvalCanBeSubmittedAfterSuspension() throws Exception {
            Flow flow = mockFlow("flow-1");
            when(flowRepository.findById("flow-1")).thenReturn(Optional.of(flow));

            DefaultFlowEngine engine = newEngine(2);
            String requestId = runUntilAwaitingApproval(engine, flow);

            // Desejado: o fluxo está AWAITING_APPROVAL e persistido, então a
            // decisão humana deve ser aceita.
            assertThatCode(() -> engine.submitApproval("flow-1", requestId, true, null))
                    .as("aprovação deve ser submetível enquanto o fluxo está AWAITING_APPROVAL")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("aprovação pode ser submetida depois de um restart do motor")
        void approvalSurvivesRestart() throws Exception {
            Flow flow = mockFlow("flow-1");
            when(flowRepository.findById("flow-1")).thenReturn(Optional.of(flow));

            String requestId = runUntilAwaitingApproval(newEngine(2), flow);

            DefaultFlowEngine afterRestart = newEngine(2);

            assertThatCode(() -> afterRestart.submitApproval("flow-1", requestId, true, null))
                    .as("aprovação persistida deve ser submetível após restart")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("requestId inválido é rejeitado também no caminho reidratado")
        void rehydratedPathRejectsUnknownRequestId() throws Exception {
            Flow flow = mockFlow("flow-1");
            when(flowRepository.findById("flow-1")).thenReturn(Optional.of(flow));

            runUntilAwaitingApproval(newEngine(2), flow);
            DefaultFlowEngine afterRestart = newEngine(2);

            assertThatThrownBy(() ->
                    afterRestart.submitApproval("flow-1", "id-errado", true, null))
                    .isInstanceOf(FlowEngineException.class)
                    .hasMessageContaining("Unknown approval requestId");
        }

        @Test
        @DisplayName("rejeição persiste STOPPED e impede uma submissão posterior")
        void rejectionMakesFlowNonSubmittable() throws Exception {
            Flow flow = mockFlow("flow-1");
            when(flowRepository.findById("flow-1")).thenReturn(Optional.of(flow));

            String requestId = runUntilAwaitingApproval(newEngine(2), flow);
            DefaultFlowEngine afterRestart = newEngine(2);

            // Rejeita a partir do estado persistido.
            afterRestart.submitApproval("flow-1", requestId, false, null);
            assertThat(durableStore.loadState("flow-1").getStatus())
                    .isEqualTo(FlowStatus.STOPPED);

            // Uma segunda decisão (aprovar) sobre o fluxo já finalizado é barrada
            // pela guarda de status.
            assertThatThrownBy(() ->
                    afterRestart.submitApproval("flow-1", requestId, true, null))
                    .isInstanceOf(FlowEngineException.class)
                    .hasMessageContaining("not awaiting approval");
        }
    }

    private static ExecutionContext freshContext() {
        return new br.com.archflow.model.engine.DefaultExecutionContext("acme", "u", "s",
                dev.langchain4j.memory.chat.MessageWindowChatMemory.builder().maxMessages(5).build());
    }

    @Nested
    @DisplayName("a decisão só sai do humano")
    class OnlyTheHumanDecides {

        @Test
        @DisplayName("resumeFlow recusa um portão com o pedido ainda aberto — não conclui sem decisão")
        void resumeRefusesAPendingGate() throws Exception {
            Flow flow = mockFlow("flow-1");
            when(flowRepository.findById("flow-1")).thenReturn(Optional.of(flow));
            DefaultFlowEngine engine = newEngine(2);
            String requestId = runUntilAwaitingApproval(engine, flow);

            var resumed = engine.resumeFlow("flow-1", freshContext());

            assertThatThrownBy(() -> resumed.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(FlowEngineException.class)
                    .hasMessageContaining("awaiting a human decision");
            FlowState persisted = durableStore.loadState("flow-1");
            assertThat(persisted.getStatus()).isEqualTo(FlowStatus.AWAITING_APPROVAL);
            assertThat(persisted.getVariables()).containsEntry(APPROVAL_REQUEST_KEY, requestId);
            // O pedido continua decidível pela fila
            assertThatCode(() -> engine.submitApproval("flow-1", requestId, true, null, "sre", null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("startFlow descarta da entrada as chaves de decisão do motor")
        void startFlowDropsReservedInputKeys() throws Exception {
            Flow flow = mockFlow("flow-1");
            when(flowRepository.findById("flow-1")).thenReturn(Optional.of(flow));
            AtomicReference<Map<String, Object>> seen = new AtomicReference<>();
            when(executionManager.executeFlow(eq(flow), any(ExecutionContext.class))).thenAnswer(inv -> {
                ExecutionContext ctx = inv.getArgument(1);
                seen.set(Map.copyOf(ctx.getState().getVariables()));
                return resultWith(ExecutionStatus.COMPLETED);
            });

            newEngine(2).startFlow("flow-1", Map.of(
                    ExecutionKeys.APPROVAL_DECISION, "APPROVED",
                    ExecutionKeys.APPROVAL_DECIDED_BY, "ceo",
                    "pedido", 42)).get(5, TimeUnit.SECONDS);

            assertThat(seen.get())
                    .containsEntry("pedido", 42)
                    .doesNotContainKeys(ExecutionKeys.APPROVAL_DECISION, ExecutionKeys.APPROVAL_DECIDED_BY);
        }
    }

    /**
     * A decisão chega enquanto a travessia original ainda está em {@code executeFlow} — um
     * cliente automático decide entre o pedido e o desenrolar. O mock decide de dentro do
     * {@code executeFlow}, o que fixa essa ordem sem depender de tempo.
     */
    @Nested
    @DisplayName("decisão com a travessia original ainda viva")
    class DecisionWhileTraversalIsLive {

        @Test
        @DisplayName("rejeição: o estado terminal da travessia original não apaga quem rejeitou")
        @SuppressWarnings("unchecked")
        void rejectionKeepsTheDecider() throws Exception {
            Flow flow = mockFlow("flow-1");
            when(flowRepository.findById("flow-1")).thenReturn(Optional.of(flow));
            DefaultFlowEngine engine = newEngine(2);
            when(executionManager.executeFlow(eq(flow), any(ExecutionContext.class))).thenAnswer(inv -> {
                String requestId = engine.requestApproval("flow-1", "gate", "proposta");
                engine.submitApproval("flow-1", requestId, false, null, "sre", "não");
                // stopFlow (mock) não interrompe nada; a travessia devolve o que veria: cancelada
                return resultWith(ExecutionStatus.CANCELLED);
            });

            engine.startFlow("flow-1", Map.of()).get(5, TimeUnit.SECONDS);

            FlowState persisted = durableStore.loadState("flow-1");
            assertThat(persisted.getStatus()).isEqualTo(FlowStatus.STOPPED);
            assertThat(persisted.getVariables())
                    .containsEntry(ExecutionKeys.APPROVAL_DECISION, "REJECTED")
                    .containsEntry(ExecutionKeys.APPROVAL_DECIDED_BY, "sre")
                    .containsEntry(ExecutionKeys.APPROVAL_COMMENT, "não")
                    .containsEntry(APPROVAL_REQUEST_KEY, "");
            assertThat((List<Map<String, Object>>) persisted.getVariables().get(ExecutionKeys.APPROVAL_HISTORY))
                    .singleElement()
                    .satisfies(e -> assertThat(e).containsEntry("decidedBy", "sre"));
        }

        @Test
        @DisplayName("aprovação: a travessia original não grava PAUSED por cima da retomada")
        void approvalLeavesTheStateToTheResume() throws Exception {
            Flow flow = mockFlow("flow-1");
            when(flowRepository.findById("flow-1")).thenReturn(Optional.of(flow));
            DefaultFlowEngine engine = newEngine(2);
            java.util.concurrent.CountDownLatch resumeEntered = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch releaseResume = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            when(executionManager.executeFlow(eq(flow), any(ExecutionContext.class))).thenAnswer(inv -> {
                if (calls.incrementAndGet() == 1) {
                    String requestId = engine.requestApproval("flow-1", "gate", "proposta");
                    engine.submitApproval("flow-1", requestId, true, null, "sre", "ok");
                    // A retomada já está rodando quando esta travessia termina
                    assertThat(resumeEntered.await(5, TimeUnit.SECONDS)).isTrue();
                    return resultWith(ExecutionStatus.PAUSED);
                }
                resumeEntered.countDown();
                releaseResume.await(5, TimeUnit.SECONDS);
                return resultWith(ExecutionStatus.COMPLETED);
            });

            try {
                engine.startFlow("flow-1", Map.of()).get(5, TimeUnit.SECONDS);

                // Antes: a original gravava PAUSED no estado que a retomada já estava usando
                FlowState meanwhile = durableStore.loadState("flow-1");
                assertThat(meanwhile.getStatus()).isNotEqualTo(FlowStatus.PAUSED);
                assertThat(meanwhile.getVariables()).containsEntry(APPROVAL_REQUEST_KEY, "");
            } finally {
                releaseResume.countDown();
            }

            long deadline = System.currentTimeMillis() + 5_000;
            while (durableStore.loadState("flow-1").getStatus() != FlowStatus.COMPLETED
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            FlowState fim = durableStore.loadState("flow-1");
            assertThat(fim.getStatus()).isEqualTo(FlowStatus.COMPLETED);
            assertThat(fim.getVariables()).containsEntry(ExecutionKeys.APPROVAL_DECIDED_BY, "sre");
        }
    }

    /**
     * RCHFLW-59: uma aprovação que não consegue retomar o fluxo não pode consumir o pedido
     * em silêncio — o estado ficava em AWAITING_APPROVAL sem pedido, fora da fila e sem
     * nunca virar FAILED, e a falha só existia numa future que ninguém observava.
     */
    @Nested
    @DisplayName("retomada que não pode acontecer")
    class ResumeThatCannotHappen {

        @Test
        @DisplayName("fluxo fora do FlowRepository: recusa a aprovação e mantém o pedido na fila")
        void unregisteredFlowKeepsTheRequestPending() throws Exception {
            Flow flow = mockFlow("flow-1");
            when(flowRepository.findById("flow-1")).thenReturn(Optional.of(flow));
            DefaultFlowEngine engine = newEngine(2);
            String requestId = runUntilAwaitingApproval(engine, flow);
            // Como uma execução do editor antes da correção (nunca registrada), ou um restart
            // com o repositório em memória.
            when(flowRepository.findById("flow-1")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> engine.submitApproval("flow-1", requestId, true, null, "sre", "ok"))
                    .isInstanceOf(FlowEngineException.class)
                    .hasMessageContaining("not registered");

            FlowState state = durableStore.loadState("flow-1");
            assertThat(state.getStatus()).isEqualTo(FlowStatus.AWAITING_APPROVAL);
            assertThat(state.getVariables())
                    .as("o pedido não foi consumido: continua decidível")
                    .containsEntry(APPROVAL_REQUEST_KEY, requestId)
                    .doesNotContainKey(ExecutionKeys.APPROVAL_DECIDED_BY);

            // Recusar não precisa da definição do fluxo — o pedido ainda tem saída.
            engine.submitApproval("flow-1", requestId, false, null, "sre", "sem fluxo")
                    .get(5, TimeUnit.SECONDS);
            assertThat(durableStore.loadState("flow-1").getStatus()).isEqualTo(FlowStatus.STOPPED);
        }

        @Test
        @DisplayName("retomada que falha na montagem: o estado vira FAILED com o erro e o decisor")
        void failedResumeSetupIsPersistedAsFailed() throws Exception {
            Flow flow = mockFlow("flow-1");
            when(flowRepository.findById("flow-1")).thenReturn(Optional.of(flow));
            DefaultFlowEngine engine = new DefaultFlowEngine(
                    executionManager, flowRepository, durableStore, flowValidator,
                    ctx -> {
                        throw new IllegalStateException("memória indisponível");
                    },
                    null, 2, 30_000);
            String requestId = runUntilAwaitingApproval(engine, flow);

            var resumed = engine.submitApproval("flow-1", requestId, true, null, "sre", "ok");
            assertThatThrownBy(() -> resumed.get(5, TimeUnit.SECONDS))
                    .hasRootCauseMessage("memória indisponível");

            long deadline = System.currentTimeMillis() + 5_000;
            while (durableStore.loadState("flow-1").getStatus() == FlowStatus.AWAITING_APPROVAL
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            FlowState state = durableStore.loadState("flow-1");
            assertThat(state.getStatus())
                    .as("antes ficava AWAITING_APPROVAL com o pedido consumido, para sempre")
                    .isEqualTo(FlowStatus.FAILED);
            assertThat(state.getError()).isNotNull();
            assertThat(state.getError().code()).isEqualTo("APPROVAL_RESUME_ERROR");
            assertThat(state.getVariables())
                    .as("falhar a retomada não apaga quem autorizou")
                    .containsEntry(ExecutionKeys.APPROVAL_DECIDED_BY, "sre")
                    .containsEntry(ExecutionKeys.APPROVAL_DECISION, "APPROVED");
        }
    }
}
