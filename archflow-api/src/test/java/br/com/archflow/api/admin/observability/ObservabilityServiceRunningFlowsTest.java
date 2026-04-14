package br.com.archflow.api.admin.observability;

import br.com.archflow.agent.streaming.RunningFlowsRegistry;
import br.com.archflow.api.admin.observability.ObservabilityDtos.RunningFlowDto;
import br.com.archflow.api.admin.observability.impl.ObservabilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("ObservabilityService — running flows")
class ObservabilityServiceRunningFlowsTest {

    private RunningFlowsRegistry registry;
    private ObservabilityService service;

    @BeforeEach
    void setUp() {
        registry = new RunningFlowsRegistry();
        service = new ObservabilityService(null, null, null, null, registry);
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
        }

        @Test
        @DisplayName("succeeds for matching tenant")
        void succeedsForMatchingTenant() {
            registry.flowStarted("flow-1", "tenant-a", 2);

            assertThatCode(() -> service.cancelFlow("tenant-a", "flow-1"))
                    .doesNotThrowAnyException();

            // Optimistic remove: flow should no longer be in registry
            assertThat(service.listRunningFlows(null)).isEmpty();
        }

        @Test
        @DisplayName("superadmin with null tenantId can cancel any flow")
        void superadminCanCancelAny() {
            registry.flowStarted("flow-1", "tenant-a", 2);

            assertThatCode(() -> service.cancelFlow(null, "flow-1"))
                    .doesNotThrowAnyException();
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
