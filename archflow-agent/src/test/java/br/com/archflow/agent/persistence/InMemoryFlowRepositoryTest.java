package br.com.archflow.agent.persistence;

import br.com.archflow.model.config.FlowConfiguration;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowMetadata;
import br.com.archflow.model.flow.FlowStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InMemoryFlowRepository")
class InMemoryFlowRepositoryTest {

    private InMemoryFlowRepository repo;

    @BeforeEach
    void setUp() {
        repo = new InMemoryFlowRepository();
    }

    @Test
    @DisplayName("save + findById round-trip")
    void saveAndFind() {
        Flow flow = stubFlow("f1");
        repo.save(flow);

        Optional<Flow> found = repo.findById("f1");
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo("f1");
    }

    @Test
    @DisplayName("findById returns empty for unknown id")
    void findUnknown() {
        assertThat(repo.findById("ghost")).isEmpty();
    }

    @Test
    @DisplayName("save again replaces existing flow")
    void saveOverwrite() {
        Flow v1 = stubFlow("f1");
        repo.save(v1);
        Flow v2 = stubFlow("f1");
        repo.save(v2);

        assertThat(repo.findById("f1")).isPresent();
    }

    @Test
    @DisplayName("clear removes all flows")
    void clear() {
        repo.save(stubFlow("f1"));
        repo.save(stubFlow("f2"));
        repo.clear();
        assertThat(repo.findById("f1")).isEmpty();
        assertThat(repo.findById("f2")).isEmpty();
    }

    @Test
    @DisplayName("delete removes the flow")
    void delete() {
        repo.save(stubFlow("f1"));
        repo.delete("f1");
        assertThat(repo.findById("f1")).isEmpty();
    }

    @Test
    @DisplayName("delete on unknown id is safe")
    void deleteUnknown() {
        repo.delete("ghost");
    }

    private Flow stubFlow(String id) {
        return new Flow() {
            @Override public String getId() { return id; }
            @Override public FlowMetadata getMetadata() { return null; }
            @Override public List<FlowStep> getSteps() { return List.of(); }
            @Override public FlowConfiguration getConfiguration() { return null; }
            @Override public void validate() {}
        };
    }
}
