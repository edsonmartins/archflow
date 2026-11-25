package br.com.archflow.api.admin.observability;

import br.com.archflow.agent.streaming.RunningFlowsRegistry;
import br.com.archflow.api.admin.observability.ObservabilityDtos.RunningFlowDto;
import br.com.archflow.api.admin.observability.impl.ObservabilityService;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.exceptions.FlowEngineException;
import br.com.archflow.engine.exceptions.FlowNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("ObservabilityService — running flows")
class ObservabilityServiceRunningFlowsTest {

    private RunningFlowsRegistry registry;
    private FlowEngine engine;
    private ObservabilityService service;

    @BeforeEach
    void setUp() {
        registry = new RunningFlowsRegistry();
        engine = mock(FlowEngine.class);
        service = new ObservabilityService(null, null, null, null, registry, engine);
    }

    @Nested
    @DisplayName("listRunningFlows")
    class ListRunningFlows {

        @Test
        @DisplayName("returns empty list when no flows are running")
        void emptyWhenNoneRunning() {
            assertThat(service.listRunningFlows(null)).isEmpty();
            assertThat(service.listRunningFlows("tenant-1")).isEmpty();
        }

        @Test
        @DisplayName("returns all running flows when no tenant filter")
        void returnsAllFlowsWithNullFilter() {
            registry.flowStarted("flow-1", "tenant-a", 3);
            registry.flowStarted("flow-2", "tenant-b", 5);

            List<RunningFlowDto> result = service.listRunningFlows(null);

            assertThat(result).hasSize(2);
            assertThat(result).extracting(RunningFlowDto::flowId)
                    .containsExactlyInAnyOrder("flow-1", "flow-2");
        }

        @Test
        @DisplayName("filters by tenant")
        void filtersByTenant() {
            registry.flowStarted("flow-1", "tenant-a", 3);
            registry.flowStarted("flow-2", "tenant-b", 5);
            registry.flowStarted("flow-3", "tenant-a", 2);

            List<RunningFlowDto> result = service.listRunningFlows("tenant-a");

            assertThat(result).hasSize(2);
            assertThat(result).extracting(RunningFlowDto::flowId)
                    .containsExactlyInAnyOrder("flow-1", "flow-3");
        }

        @Test
        @DisplayName("DTO fields are mapped correctly")
        void dtoFieldsMapped() {
            registry.flowStarted("flow-1", "tenant-x", 4);
            registry.stepStarted("flow-1", "step-2", 1);

            RunningFlowDto dto = service.listRunningFlows(null).get(0);

            assertThat(dto.flowId()).isEqualTo("flow-1");
            assertThat(dto.tenantId()).isEqualTo("tenant-x");
            assertThat(dto.stepCount()).isEqualTo(4);
            assertThat(dto.currentStepId()).isEqualTo("step-2");
            assertThat(dto.stepIndex()).isEqualTo(1);
            assertThat(dto.startedAt()).isNotNull();
            assertThat(dto.durationMs()).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("completed flow no longer appears in list")
        void completedFlowRemovedFromList() {
            registry.flowStarted("flow-1", "tenant-a", 2);
            registry.flowEnded("flow-1");

            assertThat(service.listRunningFlows(null)).isEmpty();
        }

        @Test
        @DisplayName("returns empty list when registry is null")
        void nullRegistryReturnsEmpty() {
            ObservabilityService serviceNoRegistry =
                    new ObservabilityService(null, null, null, null, null);

            assertThat(serviceNoRegistry.listRunningFlows(null)).isEmpty();
            assertThat(serviceNoRegistry.listRunningFlows("any-tenant")).isEmpty();
        }
    }

    @Nested
    @DisplayName("cancelFlow")
    class CancelFlow {

        @Test
        @DisplayName("throws when flow is not running")
        void throwsWhenNotRunning() {
            assertThatThrownBy(() -> service.cancelFlow("tenant-a", "not-running"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not-running");
        }

        @Test
        @DisplayName("throws SecurityException on cross-tenant cancel")
        void throwsOnCrossTenantCancel() {
            registry.flowStarted("flow-1", "tenant-a", 2);

            assertThatThrownBy(() -> service.cancelFlow("tenant-b", "flow-1"))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("tenant-b")
                    .hasMessageContaining("tenant-a");
            verify(engine, never()).cancel(anyString());
        }

        /**
         * RCHFLW-72: o cancelar só tirava a linha do registro e o fluxo seguia rodando, invisível.
         * Agora o pedido vai ao motor e a linha fica até o fluxo parar de fato (quem a tira é o
         * RegistryFlowLifecycleListener, no fim da execução).
         */
        @Test
        @DisplayName("matching tenant: cancels in the engine and leaves the row until the flow stops")
        void succeedsForMatchingTenant() {
            registry.flowStarted("flow-1", "tenant-a", 2);

            assertThatCode(() -> service.cancelFlow("tenant-a", "flow-1"))
                    .doesNotThrowAnyException();

            verify(engine).cancel("flow-1");
            assertThat(service.listRunningFlows(null)).extracting(RunningFlowDto::flowId)
                    .containsExactly("flow-1");
        }

        @Test
        @DisplayName("superadmin with null tenantId can cancel any flow")
        void superadminCanCancelAny() {
            registry.flowStarted("flow-1", "tenant-a", 2);

            assertThatCode(() -> service.cancelFlow(null, "flow-1"))
                    .doesNotThrowAnyException();
            verify(engine).cancel("flow-1");
        }

        @Test
        @DisplayName("engine no longer has the flow active → not running (404 upstream)")
        void engineNotFoundIsNotRunning() {
            registry.flowStarted("flow-1", "tenant-a", 2);
            doThrow(new FlowEngineException("Error canceling flow: flow-1", new FlowNotFoundException("flow-1")))
                    .when(engine).cancel("flow-1");

            assertThatThrownBy(() -> service.cancelFlow("tenant-a", "flow-1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("flow-1");
        }

        @Test
        @DisplayName("any other engine failure propagates — never reported as cancelled")
        void otherEngineFailurePropagates() {
            registry.flowStarted("flow-1", "tenant-a", 2);
            doThrow(new FlowEngineException("Error canceling flow: flow-1", new IllegalStateException("db down")))
                    .when(engine).cancel("flow-1");

            assertThatThrownBy(() -> service.cancelFlow("tenant-a", "flow-1"))
                    .isInstanceOf(FlowEngineException.class);
        }

        @Test
        @DisplayName("without an engine the cancel fails explicitly instead of pretending")
        void withoutEngineFailsExplicitly() {
            registry.flowStarted("flow-1", "tenant-a", 2);
            ObservabilityService semMotor = new ObservabilityService(null, null, null, null, registry);

            assertThatThrownBy(() -> semMotor.cancelFlow("tenant-a", "flow-1"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(registry.find("flow-1")).isPresent();
        }

        @Test
        @DisplayName("throws when registry is null")
        void throwsWhenRegistryNull() {
            ObservabilityService serviceNoRegistry =
                    new ObservabilityService(null, null, null, null, null);

            assertThatThrownBy(() -> serviceNoRegistry.cancelFlow("t", "flow-1"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
