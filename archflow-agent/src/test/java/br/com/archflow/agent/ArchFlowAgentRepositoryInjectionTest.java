package br.com.archflow.agent;

import br.com.archflow.agent.config.AgentConfig;
import br.com.archflow.agent.persistence.InMemoryStateRepository;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.model.config.FlowConfiguration;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowMetadata;
import br.com.archflow.model.flow.FlowStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that ArchFlowAgent uses caller-provided repositories instead of
 * the in-memory defaults (production deployments inject durable ones).
 */
@DisplayName("ArchFlowAgent repository injection")
class ArchFlowAgentRepositoryInjectionTest {

    /** FlowRepository that records saves so injection can be observed. */
    private static class RecordingFlowRepository implements FlowRepository {
        final Map<String, Flow> flows = new ConcurrentHashMap<>();
        final AtomicInteger saves = new AtomicInteger();

        @Override public void save(Flow flow) {
            saves.incrementAndGet();
            flows.put(flow.getId(), flow);
        }
        @Override public Optional<Flow> findById(String id) { return Optional.ofNullable(flows.get(id)); }
        @Override public void delete(String id) { flows.remove(id); }
    }

    @Test
    @DisplayName("executeFlow persists the flow through the injected repository")
    void usesInjectedFlowRepository() throws Exception {
        RecordingFlowRepository flowRepository = new RecordingFlowRepository();
        AgentConfig config = new AgentConfig.Builder().agentId("injection-test").build();

        try (ArchFlowAgent agent = new ArchFlowAgent(
                config, new InMemoryStateRepository(), flowRepository)) {
            // Setup (including repository save) happens synchronously in the
            // caller thread; the async result is irrelevant for this assertion.
            agent.executeFlow(stubFlow("flow-injected"), Map.of());
        }

        assertThat(flowRepository.saves.get()).isEqualTo(1);
        assertThat(flowRepository.findById("flow-injected")).isPresent();
    }

    @Test
    @DisplayName("legacy constructor still works (in-memory defaults)")
    void legacyConstructorStillWorks() throws Exception {
        AgentConfig config = new AgentConfig.Builder().agentId("legacy-test").build();
        try (ArchFlowAgent agent = new ArchFlowAgent(config)) {
            assertThat(agent).isNotNull();
        }
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
