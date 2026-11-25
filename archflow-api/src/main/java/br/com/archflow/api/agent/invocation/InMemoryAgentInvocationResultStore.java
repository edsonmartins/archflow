package br.com.archflow.api.agent.invocation;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Resultados em memória, limitados aos {@value #DEFAULT_CAPACITY} mais recentes: ninguém apaga um
 * resultado depois de lido, e sem teto um playground aberto num gatilho por minuto cresceria até o fim
 * do processo. Some no restart — o mesmo destino da fila em memória, que o guarda de produção recusa.
 */
public class InMemoryAgentInvocationResultStore implements AgentInvocationResultStore {

    static final int DEFAULT_CAPACITY = 1_000;

    private final Map<String, AgentInvocationResult> results;

    public InMemoryAgentInvocationResultStore() {
        this(DEFAULT_CAPACITY);
    }

    public InMemoryAgentInvocationResultStore(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.results = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, AgentInvocationResult> eldest) {
                return size() > capacity;
            }
        };
    }

    @Override
    public synchronized void save(AgentInvocationResult result) {
        results.put(result.requestId(), result);
    }

    @Override
    public synchronized Optional<AgentInvocationResult> find(String requestId) {
        return Optional.ofNullable(results.get(requestId));
    }

    @Override
    public synchronized int purgeFinishedBefore(Instant cutoff) {
        int before = results.size();
        results.values().removeIf(r -> r.status().terminal() && r.finishedAt() != null
                && r.finishedAt().isBefore(cutoff));
        return before - results.size();
    }
}
