package br.com.archflow.agent.handoff;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Manages agent handoffs — registration, resolution, and execution.
 *
 * <p>Agents register themselves with capabilities. When a handoff is requested,
 * the manager resolves the target agent and transfers control with the
 * appropriate state and history filtering.
 */
public class AgentHandoffManager {

    private static final Logger log = LoggerFactory.getLogger(AgentHandoffManager.class);

    private final Map<String, AgentRegistration> registry;
    private final List<AgentHandoff> handoffHistory;
    private final List<HandoffListener> listeners;

    public AgentHandoffManager() {
        this.registry = new ConcurrentHashMap<>();
        this.handoffHistory = new CopyOnWriteArrayList<>();
        this.listeners = new CopyOnWriteArrayList<>();
    }

    /**
     * Registers an agent with its capabilities.
     */
    public void registerAgent(String agentId, String displayName, Set<String> capabilities) {
        registry.put(agentId, new AgentRegistration(agentId, displayName, Set.copyOf(capabilities)));
        log.info("Registered agent for handoff: {} ({})", agentId, capabilities);
    }

    /**
     * Unregisters an agent.
     */
    public void unregisterAgent(String agentId) {
        registry.remove(agentId);
        log.info("Unregistered agent: {}", agentId);
    }

    /**
     * Resolves the best target agent for a given capability.
     *
     * @param capability The required capability
     * @return The agent registration, or empty if none found
     */
    public Optional<AgentRegistration> resolveByCapability(String capability) {
        return registry.values().stream()
                .filter(r -> r.capabilities().contains(capability))
                .findFirst();
    }

    /**
     * Resolves an agent by ID.
     */
    public Optional<AgentRegistration> resolveById(String agentId) {
        return Optional.ofNullable(registry.get(agentId));
    }

    /**
     * Executes a handoff, notifying listeners and recording history.
     *
     * @param handoff The handoff to execute
     * @return true if the target agent was found and notified
     */
    public boolean executeHandoff(AgentHandoff handoff) {
        Optional<AgentRegistration> target = resolveById(handoff.targetAgentId());
        if (target.isEmpty()) {
            log.warn("Handoff failed — target agent not found: {}", handoff.targetAgentId());
            return false;
        }

        handoffHistory.add(handoff);
        log.info("Handoff executed: {} → {} (mode={}, reason={})",
                handoff.sourceAgentId(), handoff.targetAgentId(),
                handoff.mode(), handoff.reason());

        notifyListeners(l -> l.onHandoff(handoff));
        return true;
    }

    /**
     * Gets all registered agents.
     */
    public Collection<AgentRegistration> getRegisteredAgents() {
        return Collections.unmodifiableCollection(registry.values());
    }

    /**
     * Gets handoff history.
     */
    public List<AgentHandoff> getHandoffHistory() {
        return Collections.unmodifiableList(handoffHistory);
    }

    /**
     * Adds a handoff listener.
     */
    public void addListener(HandoffListener listener) {
        listeners.add(listener);
    }

    private void notifyListeners(Consumer<HandoffListener> action) {
        for (HandoffListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (Exception e) {
                log.warn("Handoff listener error", e);
            }
        }
    }

    /**
     * Agent registration with capabilities.
     */
    public record AgentRegistration(
            String agentId,
            String displayName,
            Set<String> capabilities
    ) {}

    /**
     * Listener for handoff events.
     */
    @FunctionalInterface
    public interface HandoffListener {
        void onHandoff(AgentHandoff handoff);
    }
}
