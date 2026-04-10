package br.com.archflow.agent.persistence;

import br.com.archflow.model.error.ExecutionError;
import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.flow.FlowStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.*;

@DisplayName("InMemoryStateRepository — Multi-Tenant")
class InMemoryStateRepositoryTest {

    private InMemoryStateRepository repo;

    @BeforeEach
    void setUp() {
        repo = new InMemoryStateRepository();
    }

    private FlowState buildState(String tenantId, String flowId, FlowStatus status) {
        return FlowState.builder()
                .tenantId(tenantId)
                .flowId(flowId)
                .status(status)
                .variables(new HashMap<>())
                .build();
    }

    @Nested
    @DisplayName("Tenant isolation — saveState/getState")
    class TenantIsolation {

        @Test
        @DisplayName("should save and retrieve state by tenant + flowId")
        void shouldSaveAndRetrieveByTenant() {
            var state = buildState("tenant-A", "flow-1", FlowStatus.RUNNING);
            repo.saveState("tenant-A", "flow-1", state);

            var retrieved = repo.getState("tenant-A", "flow-1");
            assertThat(retrieved).isNotNull();
            assertThat(retrieved.getFlowId()).isEqualTo("flow-1");
            assertThat(retrieved.getTenantId()).isEqualTo("tenant-A");
        }

        @Test
        @DisplayName("should isolate states between tenants with same flowId")
        void shouldIsolateBetweenTenants() {
            repo.saveState("tenant-A", "flow-1", buildState("tenant-A", "flow-1", FlowStatus.RUNNING));
            repo.saveState("tenant-B", "flow-1", buildState("tenant-B", "flow-1", FlowStatus.COMPLETED));

            var stateA = repo.getState("tenant-A", "flow-1");
            var stateB = repo.getState("tenant-B", "flow-1");

            assertThat(stateA.getStatus()).isEqualTo(FlowStatus.RUNNING);
            assertThat(stateB.getStatus()).isEqualTo(FlowStatus.COMPLETED);
        }

        @Test
        @DisplayName("should return null for non-existent tenant+flow")
        void shouldReturnNullForNonExistent() {
            repo.saveState("tenant-A", "flow-1", buildState("tenant-A", "flow-1", FlowStatus.RUNNING));

            assertThat(repo.getState("tenant-B", "flow-1")).isNull();
            assertThat(repo.getState("tenant-A", "flow-999")).isNull();
        }

        @Test
        @DisplayName("should return deep copy to prevent external mutation")
        void shouldReturnDeepCopy() {
            var state = buildState("t", "f", FlowStatus.RUNNING);
            repo.saveState("t", "f", state);

            var retrieved = repo.getState("t", "f");
            retrieved.setStatus(FlowStatus.FAILED);

            var retrievedAgain = repo.getState("t", "f");
            assertThat(retrievedAgain.getStatus()).isEqualTo(FlowStatus.RUNNING);
        }
    }

    @Nested
    @DisplayName("getStatesByTenant")
    class GetStatesByTenant {

        @Test
        @DisplayName("should list all states for a tenant")
        void shouldListByTenant() {
            repo.saveState("t1", "f1", buildState("t1", "f1", FlowStatus.RUNNING));
            repo.saveState("t1", "f2", buildState("t1", "f2", FlowStatus.COMPLETED));
            repo.saveState("t2", "f1", buildState("t2", "f1", FlowStatus.PAUSED));

            assertThat(repo.getStatesByTenant("t1")).hasSize(2);
            assertThat(repo.getStatesByTenant("t2")).hasSize(1);
            assertThat(repo.getStatesByTenant("t3")).isEmpty();
        }
    }

    @Nested
    @DisplayName("Audit logs — tenant isolation")
    class AuditLogs {

        @Test
        @DisplayName("should isolate audit logs by tenant")
        void shouldIsolateAuditLogs() {
            repo.saveState("t1", "f1", buildState("t1", "f1", FlowStatus.RUNNING));
            repo.saveState("t2", "f1", buildState("t2", "f1", FlowStatus.RUNNING));

            var logsT1 = repo.getAuditLogs("t1", "f1");
            var logsT2 = repo.getAuditLogs("t2", "f1");

            assertThat(logsT1).isNotEmpty();
            assertThat(logsT2).isNotEmpty();
            assertThat(logsT1).isNotEqualTo(logsT2);
        }

        @Test
        @DisplayName("should return empty for non-existent tenant audit logs")
        void shouldReturnEmptyForNonExistent() {
            assertThat(repo.getAuditLogs("unknown", "f1")).isEmpty();
        }
    }

    @Nested
    @DisplayName("Errors — tenant isolation")
    class Errors {

        @Test
        @DisplayName("should isolate errors by tenant")
        void shouldIsolateErrors() {
            var error = ExecutionError.fromException("ERR", new RuntimeException("fail"), "test");
            repo.saveError("t1", "f1", error);

            assertThat(repo.getErrors("t1", "f1")).hasSize(1);
            assertThat(repo.getErrors("t2", "f1")).isEmpty();
        }
    }

    @Nested
    @DisplayName("clearFlow — tenant isolation")
    class ClearFlow {

        @Test
        @DisplayName("should clear only target tenant flow")
        void shouldClearOnlyTargetTenant() {
            repo.saveState("t1", "f1", buildState("t1", "f1", FlowStatus.RUNNING));
            repo.saveState("t2", "f1", buildState("t2", "f1", FlowStatus.RUNNING));

            repo.clearFlow("t1", "f1");

            assertThat(repo.getState("t1", "f1")).isNull();
            assertThat(repo.getState("t2", "f1")).isNotNull();
        }
    }

    @Nested
    @DisplayName("Backward compatibility")
    class BackwardCompat {

        @Test
        @DisplayName("single-arg saveState should use SYSTEM tenant from FlowState")
        void singleArgSaveState() {
            var state = buildState("MY_TENANT", "f1", FlowStatus.RUNNING);
            repo.saveState("f1", state);

            // Should be saved under the FlowState's tenantId
            var retrieved = repo.getState("MY_TENANT", "f1");
            assertThat(retrieved).isNotNull();
        }
    }
}
