package br.com.archflow.api.agent.mcp;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Store em memória — dev e teste.
 *
 * <p>Um laço suspenso aqui <b>não</b> sobrevive a restart, o que derrota o
 * propósito em produção: use a implementação JDBC. O
 * {@code ProductionReadinessGuard} já reclama de stores em memória fora de dev.
 */
public final class InMemoryMcpAgentStateStore implements McpAgentStateStore {

    private final Map<String, McpAgentState> byRunId = new ConcurrentHashMap<>();

    @Override
    public void save(McpAgentState state) {
        if (state != null && state.runId() != null) {
            byRunId.put(state.runId(), state);
        }
    }

    @Override
    public Optional<McpAgentState> findByRunId(String runId) {
        return runId == null ? Optional.empty() : Optional.ofNullable(byRunId.get(runId));
    }

    @Override
    public Optional<McpAgentState> findByRequestId(String requestId) {
        if (requestId == null) {
            return Optional.empty();
        }
        return byRunId.values().stream()
                .filter(s -> s.pending() != null && requestId.equals(s.pending().requestId()))
                .findFirst();
    }

    @Override
    public boolean delete(String runId) {
        return runId != null && byRunId.remove(runId) != null;
    }

    @Override
    public List<McpAgentState> findPendingByTenant(String tenantId) {
        return byRunId.values().stream()
                .filter(s -> s.pending() != null)
                .filter(s -> tenantId == null || tenantId.isBlank()
                        || "all".equalsIgnoreCase(tenantId)
                        || tenantId.equals(s.tenantId()))
                .sorted((a, b) -> {
                    if (a.suspendedAt() == null || b.suspendedAt() == null) {
                        return 0;
                    }
                    return a.suspendedAt().compareTo(b.suspendedAt());
                })
                .toList();
    }
}
