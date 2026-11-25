package br.com.archflow.agent.streaming;

import br.com.archflow.agent.streaming.domain.FlowEvent;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.error.ExecutionError;
import br.com.archflow.model.error.ExecutionErrorType;
import br.com.archflow.model.flow.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RegistryFlowLifecycleListener")
class RegistryFlowLifecycleListenerTest {

    private EventStreamRegistry streamRegistry;
    private RunningFlowsRegistry runningRegistry;
    private RegistryFlowLifecycleListener listener;

    @Mock Flow flow;
    @Mock FlowStep step;
    @Mock ExecutionContext context;
    @Mock FlowResult result;

    @BeforeEach
    void setUp() {
        streamRegistry = new EventStreamRegistry(60_000, 300_000);
        runningRegistry = new RunningFlowsRegistry();
        listener = new RegistryFlowLifecycleListener(streamRegistry, runningRegistry);

        lenient().when(flow.getId()).thenReturn("flow-1");
        lenient().when(flow.getSteps()).thenReturn(List.of());
        lenient().when(step.getId()).thenReturn("step-1");
        lenient().when(context.getState()).thenReturn(null);
        lenient().when(context.getTenantId()).thenReturn("tenant-x");
    }

    @Nested
    @DisplayName("RunningFlowsRegistry updates")
    class RegistryUpdates {

        @Test
        @DisplayName("flowStarted registers flow with correct step count")
        void flowStartedRegistersFlow() {
            listener.onFlowStarted(flow, context, 5);

            var active = runningRegistry.find("flow-1");
            assertThat(active).isPresent();
            assertThat(active.get().stepCount()).isEqualTo(5);
            assertThat(active.get().tenantId()).isEqualTo("tenant-x");
        }

        @Test
        @DisplayName("stepStarted updates current step in registry")
        void stepStartedUpdatesStep() {
            listener.onFlowStarted(flow, context, 3);
            listener.onStepStarted(flow, step, context, 1, 3);

            var active = runningRegistry.find("flow-1");
            assertThat(active).isPresent();
            assertThat(active.get().currentStepId()).isEqualTo("step-1");
            assertThat(active.get().stepIndex()).isEqualTo(1);
        }

        @Test
        @DisplayName("flowCompleted removes flow from registry")
        void flowCompletedRemovesFlow() {
            listener.onFlowStarted(flow, context, 2);
            assertThat(runningRegistry.find("flow-1")).isPresent();

            listener.onFlowCompleted(flow, context, result, 100L);
            assertThat(runningRegistry.find("flow-1")).isEmpty();
        }

        @Test
        @DisplayName("flowFailed removes flow from registry")
        void flowFailedRemovesFlow() {
            listener.onFlowStarted(flow, context, 2);
            listener.onFlowFailed(flow, context, new RuntimeException("oops"), 50L);

            assertThat(runningRegistry.find("flow-1")).isEmpty();
        }
    }

    @Nested
    @DisplayName("SSE broadcasting")
    class Broadcasting {

        @Test
        @DisplayName("flow-specific and admin channels both receive FLOW_STARTED")
        void broadcastsToFlowAndAdminChannels() {
            var flowEvents = new CopyOnWriteArrayList<ArchflowEvent>();
            var adminEvents = new CopyOnWriteArrayList<ArchflowEvent>();

            streamRegistry.createEmitter("flow-1").onSend(flowEvents::add);
            streamRegistry.createEmitter("__admin__:tenant-x").onSend(adminEvents::add);

            listener.onFlowStarted(flow, context, 3);

            assertThat(flowEvents).hasSize(1);
            assertThat(flowEvents.get(0).getType()).isEqualTo(ArchflowEventType.FLOW_STARTED);
            assertThat(adminEvents).hasSize(1);
            assertThat(adminEvents.get(0).getType()).isEqualTo(ArchflowEventType.FLOW_STARTED);
        }

        /**
         * RCHFLW-50: o stream SSE só abre {@code tenant:sessão}. Sem esta entrega, a tela de eventos
         * ao vivo conectava na execução e só recebia o {@code connected}.
         */
        @Test
        @DisplayName("fluxo e passos chegam ao canal tenant:execução, com o tenant do contexto")
        void deliversToTheExecutionTenantChannel() {
            var tenantEvents = new CopyOnWriteArrayList<ArchflowEvent>();
            var otherTenant = new CopyOnWriteArrayList<ArchflowEvent>();
            streamRegistry.createEmitter("tenant-x", "flow-1").onSend(tenantEvents::add);
            streamRegistry.createEmitter("tenant-y", "flow-1").onSend(otherTenant::add);
            when(result.getStatus()).thenReturn(ExecutionStatus.COMPLETED);

            listener.onFlowStarted(flow, context, 1);
            listener.onStepStarted(flow, step, context, 0, 1);
            listener.onStepCompleted(flow, step, context, 5L);
            listener.onFlowCompleted(flow, context, result, 10L);

            assertThat(tenantEvents).extracting(ArchflowEvent::getType).containsExactly(
                    ArchflowEventType.FLOW_STARTED, ArchflowEventType.STEP_STARTED,
                    ArchflowEventType.STEP_COMPLETED, ArchflowEventType.FLOW_COMPLETED);
            assertThat(otherTenant).as("a mesma execução vista de outro tenant").isEmpty();
        }

        /**
         * A variável "tenantId" é dado de entrada de quem chama a API. Lida dela, o chamador
         * escolheria em que tenant os eventos (e a linha do Running) apareceriam.
         */
        @Test
        @DisplayName("a variável 'tenantId' do input não escolhe o canal nem o tenant do Running")
        void inputVariableDoesNotPickTheTenant() {
            var victim = new CopyOnWriteArrayList<ArchflowEvent>();
            var real = new CopyOnWriteArrayList<ArchflowEvent>();
            streamRegistry.createEmitter("tenant-vitima", "flow-1").onSend(victim::add);
            streamRegistry.createEmitter("__admin__:tenant-vitima").onSend(victim::add);
            streamRegistry.createEmitter("tenant-x", "flow-1").onSend(real::add);
            lenient().when(context.get("tenantId")).thenReturn(java.util.Optional.of("tenant-vitima"));

            listener.onFlowStarted(flow, context, 1);

            assertThat(victim).isEmpty();
            assertThat(real).hasSize(1);
            assertThat(runningRegistry.find("flow-1").orElseThrow().tenantId()).isEqualTo("tenant-x");
        }

        /** O canal do tenant é entrega extra: os ouvintes globais (protobuf) não veem o evento de novo. */
        @Test
        @DisplayName("ouvintes globais recebem o evento só pelos broadcasts de antes")
        void tenantDeliveryDoesNotRenotifyGlobalListeners() {
            var global = new CopyOnWriteArrayList<ArchflowEvent>();
            streamRegistry.addGlobalListener(global::add);

            listener.onFlowStarted(flow, context, 1);

            assertThat(global).as("canal do fluxo + canal admin, como antes do RCHFLW-50").hasSize(2);
        }

        @Test
        @DisplayName("FLOW_COMPLETED event includes durationMs")
        void completedEventIncludesDuration() {
            var events = new CopyOnWriteArrayList<ArchflowEvent>();
            streamRegistry.createEmitter("flow-1").onSend(events::add);

            listener.onFlowCompleted(flow, context, result, 250L);

            assertThat(events).hasSize(1);
            assertThat(events.get(0).getData("durationMs")).isEqualTo(250L);
        }

        @Test
        @DisplayName("FlowResult FAILED (passo falhou sem aresta de erro) vira FLOW_FAILED com o motivo")
        void failedResultEmitsFlowFailed() {
            var flowEvents = new CopyOnWriteArrayList<ArchflowEvent>();
            var adminEvents = new CopyOnWriteArrayList<ArchflowEvent>();
            streamRegistry.createEmitter("flow-1").onSend(flowEvents::add);
            streamRegistry.createEmitter("__admin__:tenant-x").onSend(adminEvents::add);
            when(result.getStatus()).thenReturn(ExecutionStatus.FAILED);
            when(result.getErrors()).thenReturn(List.of(
                    ExecutionError.of("STEP_FAILED", "provedor recusou a chave", ExecutionErrorType.EXECUTION),
                    ExecutionError.of("STEP_FAILED", "timeout no passo b", ExecutionErrorType.EXECUTION)));

            listener.onFlowCompleted(flow, context, result, 40L);

            assertThat(flowEvents).extracting(ArchflowEvent::getType)
                    .containsExactly(ArchflowEventType.FLOW_FAILED);
            assertThat(flowEvents.get(0).getData("error"))
                    .isEqualTo("provedor recusou a chave; timeout no passo b");
            assertThat(flowEvents.get(0).getData("errorType")).isEqualTo("EXECUTION");
            assertThat(flowEvents.get(0).getData("durationMs")).isEqualTo(40L);
            assertThat(adminEvents).extracting(ArchflowEvent::getType)
                    .containsExactly(ArchflowEventType.FLOW_FAILED);
        }

        @ParameterizedTest
        @EnumSource(value = ExecutionStatus.class, names = {"COMPLETED", "CANCELLED", "PAUSED"})
        @DisplayName("os demais desfechos saem como FLOW_COMPLETED levando o status final")
        void otherOutcomesCarryStatus(ExecutionStatus status) {
            var events = new CopyOnWriteArrayList<ArchflowEvent>();
            streamRegistry.createEmitter("flow-1").onSend(events::add);
            when(result.getStatus()).thenReturn(status);

            listener.onFlowCompleted(flow, context, result, 10L);

            assertThat(events).extracting(ArchflowEvent::getType)
                    .containsExactly(ArchflowEventType.FLOW_COMPLETED);
            assertThat(events.get(0).getData("status")).isEqualTo(status.name());
        }

        @Test
        @DisplayName("STEP_STARTED event includes step index and count")
        void stepStartedEventIncludesIndex() {
            var events = new CopyOnWriteArrayList<ArchflowEvent>();
            streamRegistry.createEmitter("flow-1").onSend(events::add);
            lenient().when(flow.getSteps()).thenReturn(List.of(step));

            listener.onStepStarted(flow, step, context, 0, 1);

            assertThat(events).hasSize(1);
            assertThat(events.get(0).getData("stepIndex")).isEqualTo(0);
            assertThat(events.get(0).getData("stepCount")).isEqualTo(1);
        }

        @Test
        @DisplayName("STEP_COMPLETED/FAILED/SKIPPED repeat the STEP_STARTED index, not the declaration position")
        void finishedEventRepeatsStartedIndex() {
            // Ordem de declaração diferente da de travessia (decision → switch → condition,
            // com um ramo não visitado no meio) — o caso que dava 0,3,1 no fim para 0,1,2 no início.
            FlowStep decision = stepWithId("decision");
            FlowStep unused = stepWithId("ramo-nao-visitado");
            FlowStep condition = stepWithId("condition");
            FlowStep sw = stepWithId("switch");
            lenient().when(flow.getSteps()).thenReturn(List.of(decision, condition, unused, sw));

            var events = new CopyOnWriteArrayList<ArchflowEvent>();
            streamRegistry.createEmitter("flow-1").onSend(events::add);

            listener.onStepStarted(flow, decision, context, 0, 3);
            listener.onStepCompleted(flow, decision, context, 5L);
            listener.onStepStarted(flow, sw, context, 1, 3);
            listener.onStepFailed(flow, sw, context, new RuntimeException("x"), 5L);
            listener.onStepStarted(flow, condition, context, 2, 3);
            listener.onStepSkipped(flow, condition, context);

            assertThat(events).extracting(ArchflowEvent::getType).containsExactly(
                    ArchflowEventType.STEP_STARTED, ArchflowEventType.STEP_COMPLETED,
                    ArchflowEventType.STEP_STARTED, ArchflowEventType.STEP_FAILED,
                    ArchflowEventType.STEP_STARTED, ArchflowEventType.STEP_SKIPPED);
            assertThat(events).extracting(e -> e.getData("stepIndex"))
                    .containsExactly(0, 0, 1, 1, 2, 2);
        }

        @Test
        @DisplayName("STEP_SUSPENDED (portão aguardando) fecha o STEP_STARTED com o mesmo índice")
        void suspendedEventRepeatsStartedIndex() {
            FlowStep first = stepWithId("a");
            lenient().when(flow.getSteps()).thenReturn(List.of(first, step));
            var events = new CopyOnWriteArrayList<ArchflowEvent>();
            streamRegistry.createEmitter("flow-1").onSend(events::add);

            listener.onStepStarted(flow, step, context, 0, 2);
            listener.onStepSuspended(flow, step, context, 9L);

            // Um evento próprio, não STEP_COMPLETED: é ele que o AG-UI e o canvas leem como
            // "aguardando aprovação" em vez de ✓ (RCHFLW-32).
            assertThat(events).extracting(ArchflowEvent::getType).containsExactly(
                    ArchflowEventType.STEP_STARTED, ArchflowEventType.STEP_SUSPENDED);
            assertThat(events.get(1).getData("stepIndex")).isEqualTo(0);
            assertThat(events.get(1).getData("stepId")).isEqualTo("step-1");
            assertThat(events.get(1).getData("durationMs")).isEqualTo(9L);
        }

        @Test
        @DisplayName("finish without a recorded start falls back to the declaration position")
        void finishWithoutStartFallsBackToDeclarationIndex() {
            FlowStep first = stepWithId("a");
            lenient().when(flow.getSteps()).thenReturn(List.of(first, step));
            var events = new CopyOnWriteArrayList<ArchflowEvent>();
            streamRegistry.createEmitter("flow-1").onSend(events::add);

            listener.onStepCompleted(flow, step, context, 1L);

            assertThat(events).hasSize(1);
            assertThat(events.get(0).getData("stepIndex")).isEqualTo(1);
        }

        @Test
        @DisplayName("a new execution of the same flow does not reuse an index left by the previous one")
        void flowEndForgetsStartedIndexes() {
            lenient().when(flow.getSteps()).thenReturn(List.of(step));
            listener.onStepStarted(flow, step, context, 7, 8);
            listener.onFlowFailed(flow, context, new RuntimeException("cancelado"), 1L);

            var events = new CopyOnWriteArrayList<ArchflowEvent>();
            streamRegistry.createEmitter("flow-1").onSend(events::add);
            listener.onStepCompleted(flow, step, context, 1L);

            assertThat(events).hasSize(1);
            assertThat(events.get(0).getData("stepIndex")).isEqualTo(0);
        }

        private FlowStep stepWithId(String id) {
            FlowStep s = mock(FlowStep.class);
            lenient().when(s.getId()).thenReturn(id);
            return s;
        }
    }

    @Nested
    @DisplayName("exception safety")
    class ExceptionSafety {

        @Test
        @DisplayName("exception in broadcast does not propagate to caller")
        void broadcastExceptionSwallowed() {
            // No emitters registered — broadcast does nothing, no exception
            assertThatCode(() -> listener.onFlowStarted(flow, context, 1))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("null tenantId is handled gracefully — no admin broadcast")
        void nullTenantIdHandled() {
            when(context.getTenantId()).thenReturn(null);
            assertThatCode(() -> listener.onFlowStarted(flow, context, 2))
                    .doesNotThrowAnyException();
        }
    }
}
