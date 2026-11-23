package br.com.archflow.agent.streaming;

import br.com.archflow.agent.streaming.domain.FlowEvent;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
        lenient().when(context.get("tenantId")).thenReturn(java.util.Optional.of("tenant-x"));
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
            when(context.get("tenantId")).thenReturn(java.util.Optional.empty());
            assertThatCode(() -> listener.onFlowStarted(flow, context, 2))
                    .doesNotThrowAnyException();
        }
    }
}
