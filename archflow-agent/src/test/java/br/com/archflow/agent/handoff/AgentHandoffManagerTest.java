package br.com.archflow.agent.handoff;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AgentHandoffManager")
class AgentHandoffManagerTest {

    private AgentHandoffManager manager;

    @BeforeEach
    void setUp() {
        manager = new AgentHandoffManager();
    }

    @Test
    @DisplayName("should register and resolve agent by ID")
    void shouldRegisterAndResolveById() {
        manager.registerAgent("agent-1", "Billing Agent", Set.of("billing", "payments"));

        var result = manager.resolveById("agent-1");
        assertTrue(result.isPresent());
        assertEquals("Billing Agent", result.get().displayName());
    }

    @Test
    @DisplayName("should resolve agent by capability")
    void shouldResolveByCapability() {
        manager.registerAgent("agent-1", "Billing", Set.of("billing", "payments"));
        manager.registerAgent("agent-2", "Tech Support", Set.of("technical", "bugs"));

        var result = manager.resolveByCapability("technical");
        assertTrue(result.isPresent());
        assertEquals("agent-2", result.get().agentId());
    }

    @Test
    @DisplayName("should return empty when capability not found")
    void shouldReturnEmptyForUnknownCapability() {
        manager.registerAgent("agent-1", "Billing", Set.of("billing"));

        var result = manager.resolveByCapability("shipping");
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("should execute handoff successfully")
    void shouldExecuteHandoff() {
        manager.registerAgent("agent-1", "Source", Set.of());
        manager.registerAgent("agent-2", "Target", Set.of("billing"));

        AgentHandoff handoff = AgentHandoff.manager("agent-1", "agent-2",
                Map.of("orderId", "12345"), "Customer needs billing help");

        assertTrue(manager.executeHandoff(handoff));
        assertEquals(1, manager.getHandoffHistory().size());
    }

    @Test
    @DisplayName("should fail handoff when target not registered")
    void shouldFailHandoffForUnknownTarget() {
        manager.registerAgent("agent-1", "Source", Set.of());

        AgentHandoff handoff = AgentHandoff.peer("agent-1", "unknown-agent",
                Map.of(), "test");

        assertFalse(manager.executeHandoff(handoff));
    }

    @Test
    @DisplayName("should notify listeners on handoff")
    void shouldNotifyListeners() {
        AtomicReference<AgentHandoff> captured = new AtomicReference<>();
        manager.addListener(captured::set);
        manager.registerAgent("a", "A", Set.of());
        manager.registerAgent("b", "B", Set.of());

        AgentHandoff handoff = AgentHandoff.peer("a", "b", Map.of(), "escalation");
        manager.executeHandoff(handoff);

        assertNotNull(captured.get());
        assertEquals("a", captured.get().sourceAgentId());
        assertEquals("b", captured.get().targetAgentId());
    }

    @Test
    @DisplayName("should unregister agent")
    void shouldUnregisterAgent() {
        manager.registerAgent("agent-1", "Agent", Set.of("cap"));
        manager.unregisterAgent("agent-1");

        assertTrue(manager.resolveById("agent-1").isEmpty());
    }

    @Test
    @DisplayName("should create handoff with input filter")
    void shouldCreateHandoffWithInputFilter() {
        AgentHandoff handoff = AgentHandoff.peer("a", "b", Map.of(), "test")
                .withInputFilter(AgentHandoff.InputFilter.SUMMARY_ONLY);

        assertEquals(AgentHandoff.InputFilter.SUMMARY_ONLY, handoff.inputFilter());
        assertEquals(AgentHandoff.HandoffMode.PEER, handoff.mode());
    }
}
