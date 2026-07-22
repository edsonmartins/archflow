package br.com.archflow.engine.core;

import br.com.archflow.engine.exceptions.FlowEngineException;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.engine.validation.FlowValidator;
import br.com.archflow.model.engine.ExecutionContext;
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
}
