package br.com.archflow.agent.streaming;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of currently running flows.
 *
 * <p>Updated by {@link RegistryFlowLifecycleListener} as lifecycle events arrive.
 * Thread-safe: all mutations use {@link ConcurrentHashMap} atomic operations.
 */
public class RunningFlowsRegistry {

    private final ConcurrentHashMap<String, ActiveFlow> active = new ConcurrentHashMap<>();

    /**
     * Records that a flow has started.
     *
     * @param flowId    flow identifier
     * @param tenantId  tenant identifier (null-safe)
     * @param stepCount total number of steps
     */
    public void flowStarted(String flowId, String tenantId, int stepCount) {
        active.put(flowId, new ActiveFlow(flowId, tenantId, Instant.now(),
                null, -1, stepCount));
    }

    /**
     * Updates the current step being executed.
     *
     * @param flowId    flow identifier
     * @param stepId    current step identifier
     * @param stepIndex 0-based step index
     */
    public void stepStarted(String flowId, String stepId, int stepIndex) {
        active.computeIfPresent(flowId, (k, existing) ->
                new ActiveFlow(existing.flowId(), existing.tenantId(), existing.startedAt(),
                        stepId, stepIndex, existing.stepCount()));
    }

    /**
     * Removes a flow from the registry (completed or failed).
     *
     * @param flowId flow identifier
     */
    public void flowEnded(String flowId) {
        active.remove(flowId);
    }

    /**
     * Returns an unmodifiable snapshot of all active flows.
     *
     * @return list of active flows at this instant
     */
    public List<ActiveFlow> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(active.values()));
    }

    /**
     * Returns a snapshot filtered by tenant.
     *
     * @param tenantId tenant identifier
     * @return list of active flows for the given tenant
     */
    public List<ActiveFlow> snapshotForTenant(String tenantId) {
        List<ActiveFlow> result = new ArrayList<>();
        for (ActiveFlow flow : active.values()) {
            if (tenantId.equals(flow.tenantId())) {
                result.add(flow);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Finds a specific active flow by ID.
     *
     * @param flowId flow identifier
     * @return Optional containing the flow if active
     */
    public Optional<ActiveFlow> find(String flowId) {
        return Optional.ofNullable(active.get(flowId));
    }

    /**
     * Returns the number of currently running flows.
     *
     * @return active flow count
     */
    public int size() {
        return active.size();
    }

    // ----------------------------------------------------------------

    /**
     * Immutable snapshot of an active flow's current state.
     */
    public record ActiveFlow(
            String flowId,
            String tenantId,
            Instant startedAt,
            String currentStepId,
            int stepIndex,
            int stepCount
    ) {
        /**
         * Computes elapsed time in milliseconds since the flow started.
         *
         * @return duration in milliseconds
         */
        public long durationMs() {
            return System.currentTimeMillis() - startedAt.toEpochMilli();
        }
    }
}
