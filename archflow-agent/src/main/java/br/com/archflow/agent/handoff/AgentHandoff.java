package br.com.archflow.agent.handoff;

import java.time.Instant;
import java.util.*;

/**
 * Represents a handoff (transfer of control) between agents.
 *
 * <p>Supports two modes:
 * <ul>
 *   <li><b>Manager</b>: A central agent treats other agents as tools</li>
 *   <li><b>Peer</b>: Direct peer-to-peer transfer between agents</li>
 * </ul>
 *
 * <p>Inspired by OpenAI Agents SDK handoff pattern with input filtering
 * to control which conversation history the receiving agent sees.
 */
public record AgentHandoff(
        String id,
        String sourceAgentId,
        String targetAgentId,
        HandoffMode mode,
        Map<String, Object> transferState,
        InputFilter inputFilter,
        String reason,
        Instant timestamp
) {
    public AgentHandoff {
        Objects.requireNonNull(sourceAgentId, "sourceAgentId required");
        Objects.requireNonNull(targetAgentId, "targetAgentId required");
        Objects.requireNonNull(mode, "mode required");
        if (id == null) id = UUID.randomUUID().toString();
        transferState = transferState == null ? Map.of() : Map.copyOf(transferState);
        if (inputFilter == null) inputFilter = InputFilter.PASS_ALL;
        if (timestamp == null) timestamp = Instant.now();
    }

    public static AgentHandoff manager(String sourceId, String targetId, Map<String, Object> state, String reason) {
        return new AgentHandoff(null, sourceId, targetId, HandoffMode.MANAGER, state, InputFilter.PASS_ALL, reason, null);
    }

    public static AgentHandoff peer(String sourceId, String targetId, Map<String, Object> state, String reason) {
        return new AgentHandoff(null, sourceId, targetId, HandoffMode.PEER, state, InputFilter.PASS_ALL, reason, null);
    }

    public AgentHandoff withInputFilter(InputFilter filter) {
        return new AgentHandoff(id, sourceAgentId, targetAgentId, mode, transferState, filter, reason, timestamp);
    }

    /**
     * Mode of handoff transfer.
     */
    public enum HandoffMode {
        /** Central agent treats sub-agents as tools */
        MANAGER,
        /** Direct peer-to-peer transfer */
        PEER
    }

    /**
     * Controls what conversation history the receiving agent sees.
     */
    public enum InputFilter {
        /** Pass all history to the target agent */
        PASS_ALL,
        /** Pass only the last N messages */
        LAST_N,
        /** Pass only the summary */
        SUMMARY_ONLY,
        /** Pass no history, only transfer state */
        STATE_ONLY
    }
}
