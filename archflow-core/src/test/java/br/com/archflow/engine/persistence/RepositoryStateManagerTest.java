package br.com.archflow.engine.persistence;

import br.com.archflow.model.flow.AuditLog;
import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.flow.FlowStatus;
import br.com.archflow.model.flow.StateUpdate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RepositoryStateManager")
class RepositoryStateManagerTest {

    /** StateRepository de teste, apoiado num mapa. */
    private static class FakeStateRepository implements StateRepository {
        final Map<String, FlowState> states = new HashMap<>();

        @Override public void saveState(String flowId, FlowState state) { states.put(flowId, state); }
        @Override public FlowState getState(String flowId) { return states.get(flowId); }
        @Override public void saveAuditLog(String flowId, AuditLog log) { }
    }

    private FakeStateRepository repo;
    private RepositoryStateManager manager;

    @BeforeEach
    void setUp() {
        repo = new FakeStateRepository();
        manager = new RepositoryStateManager(repo);
    }

    @Test
    @DisplayName("saveState/loadState delegam ao repositório")
    void saveAndLoad() {
        FlowState state = FlowState.builder()
                .flowId("f1").status(FlowStatus.RUNNING).build();

        manager.saveState("f1", state);

        assertThat(manager.loadState("f1")).isSameAs(state);
        assertThat(repo.states).containsKey("f1");
    }

    @Test
    @DisplayName("loadState desconhecido retorna null")
    void loadUnknown() {
        assertThat(manager.loadState("ghost")).isNull();
    }

    @Test
    @DisplayName("updateState aplica a mudança e persiste de volta")
    void updateAppliesAndPersists() {
        FlowState state = FlowState.builder()
                .flowId("f1").status(FlowStatus.RUNNING)
                .variables(new HashMap<>()).build();
        manager.saveState("f1", state);

        StateUpdate update = StateUpdate.builder()
                .status(FlowStatus.COMPLETED)
                .stepId("step-2")
                .build();
        manager.updateState("f1", update);

        FlowState reloaded = manager.loadState("f1");
        assertThat(reloaded.getStatus()).isEqualTo(FlowStatus.COMPLETED);
        assertThat(reloaded.getCurrentStepId()).isEqualTo("step-2");
    }

    @Test
    @DisplayName("updateState em flow inexistente é no-op")
    void updateUnknownIsNoop() {
        manager.updateState("ghost", StateUpdate.builder().status(FlowStatus.FAILED).build());
        assertThat(repo.states).isEmpty();
    }

    @Test
    @DisplayName("construtor rejeita repositório nulo")
    void rejectsNull() {
        assertThatThrownBy(() -> new RepositoryStateManager(null))
                .isInstanceOf(NullPointerException.class);
    }
}
