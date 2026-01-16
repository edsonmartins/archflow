package br.com.archflow.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rastreador de execuções com suporte a hierarquia parent-child.
 *
 * <p>Este tracker mantém um registro de todas as execuções em andamento
 * e permite consulta de hierarquia de execuções aninhadas.
 */
public class ExecutionTracker {

    private static final Logger log = LoggerFactory.getLogger(ExecutionTracker.class);

    private final ConcurrentHashMap<String, ExecutionRecord> executions;
    private final ConcurrentHashMap<String, Set<String>> rootChildren;
    private final AtomicInteger sequenceGenerator;

    public ExecutionTracker() {
        this.executions = new ConcurrentHashMap<>();
        this.rootChildren = new ConcurrentHashMap<>();
        this.sequenceGenerator = new AtomicInteger(0);
    }

    /**
     * Inicia o rastreamento de uma execução raiz.
     *
     * @param type Tipo de execução
     * @return ExecutionId criado
     */
    public ExecutionId startRoot(ExecutionId.ExecutionType type) {
        ExecutionId executionId = ExecutionId.createRoot(type);
        return startTracking(executionId);
    }

    /**
     * Inicia o rastreamento de uma execução filha.
     *
     * @param parentId ID da execução pai
     * @param type     Tipo da execução filha
     * @return ExecutionId criado
     */
    public ExecutionId startChild(String parentId, ExecutionId.ExecutionType type) {
        ExecutionRecord parent = executions.get(parentId);
        if (parent == null) {
            throw new IllegalArgumentException("Parent execution not found: " + parentId);
        }

        ExecutionId executionId = parent.executionId.createChild(type);
        executionId = incrementSequence(executionId);

        // Adiciona à lista de filhos do pai
        parent.children.add(executionId.getId());

        log.trace("[{}] Iniciando execução filha de {}",
                executionId.getId(), parentId);

        return startTracking(executionId);
    }

    /**
     * Incrementa o sequencial do ExecutionId.
     */
    private ExecutionId incrementSequence(ExecutionId executionId) {
        int nextSeq = sequenceGenerator.incrementAndGet();
        return ExecutionId.builder()
                .type(executionId.getType())
                .rootId(executionId.getRootId())
                .parentId(executionId.getParentId())
                .sequence(nextSeq)
                .depth(executionId.getDepth())
                .build();
    }

    /**
     * Registra uma execução para rastreamento.
     */
    private ExecutionId startTracking(ExecutionId executionId) {
        ExecutionRecord record = new ExecutionRecord(executionId);
        executions.put(executionId.getId(), record);

        // Adiciona à lista de filhos da raiz
        String rootId = executionId.getRootId();
        rootChildren.computeIfAbsent(rootId, k -> new HashSet<>())
                    .add(executionId.getId());

        return executionId;
    }

    /**
     * Finaliza uma execução com sucesso.
     *
     * @param executionId ID da execução
     * @param result      Resultado da execução
     */
    public void complete(String executionId, ToolResult<?> result) {
        ExecutionRecord record = executions.get(executionId);
        if (record == null) {
            log.warn("Tentativa de completar execução não rastreada: {}", executionId);
            return;
        }

        record.endTime = Instant.now();
        record.status = ExecutionStatus.COMPLETED;
        record.result = Optional.ofNullable(result);

        log.debug("[{}] Execução completada em {}ms",
                executionId, record.getDurationMillis());
    }

    /**
     * Finaliza uma execução com erro.
     *
     * @param executionId ID da execução
     * @param error       Erro ocorrido
     */
    public void fail(String executionId, Throwable error) {
        ExecutionRecord record = executions.get(executionId);
        if (record == null) {
            log.warn("Tentativa de marcar erro em execução não rastreada: {}", executionId);
            return;
        }

        record.endTime = Instant.now();
        record.status = ExecutionStatus.FAILED;
        record.error = Optional.ofNullable(error);

        log.debug("[{}] Execução falhou após {}ms: {}",
                executionId, record.getDurationMillis(),
                error != null ? error.getMessage() : "null");
    }

    /**
     * Retorna informações sobre uma execução.
     *
     * @param executionId ID da execução
     * @return Registro da execução, ou null se não existir
     */
    public ExecutionRecord getRecord(String executionId) {
        return executions.get(executionId);
    }

    /**
     * Retorna todos os filhos diretos de uma execução.
     *
     * @param parentId ID da execução pai
     * @return Lista de IDs dos filhos
     */
    public List<String> getChildren(String parentId) {
        ExecutionRecord parent = executions.get(parentId);
        if (parent == null) {
            return List.of();
        }
        return new ArrayList<>(parent.children);
    }

    /**
     * Retorna a hierarquia completa de uma execução raiz.
     *
     * @param rootId ID da execução raiz
     * @return Lista de registros em ordem de execução
     */
    public List<ExecutionRecord> getHierarchy(String rootId) {
        List<ExecutionRecord> hierarchy = new ArrayList<>();
        buildHierarchy(rootId, hierarchy);
        return hierarchy;
    }

    private void buildHierarchy(String executionId, List<ExecutionRecord> hierarchy) {
        ExecutionRecord record = executions.get(executionId);
        if (record != null) {
            hierarchy.add(record);
            for (String childId : record.children) {
                buildHierarchy(childId, hierarchy);
            }
        }
    }

    /**
     * Retorna todas as execuções em andamento.
     *
     * @return Lista de ExecutionIds em andamento
     */
    public List<String> getActiveExecutions() {
        List<String> active = new ArrayList<>();
        for (ExecutionRecord record : executions.values()) {
            if (record.status == ExecutionStatus.RUNNING) {
                active.add(record.executionId.getId());
            }
        }
        return active;
    }

    /**
     * Remove uma execução do rastreamento (e seus filhos).
     *
     * @param executionId ID da execução
     */
    public void remove(String executionId) {
        ExecutionRecord record = executions.remove(executionId);
        if (record != null) {
            // Remove filhos recursivamente
            for (String childId : record.children) {
                remove(childId);
            }

            // Remove da lista de filhos da raiz
            String rootId = record.executionId.getRootId();
            Set<String> children = rootChildren.get(rootId);
            if (children != null) {
                children.remove(executionId);
                if (children.isEmpty()) {
                    rootChildren.remove(rootId);
                }
            }

            log.trace("[{}] Execução removida do tracker", executionId);
        }
    }

    /**
     * Remove execuções antigas (limpeza).
     *
     * @param olderThan Idade mínima para manter
     */
    public void cleanup(Instant olderThan) {
        Iterator<Map.Entry<String, ExecutionRecord>> it = executions.entrySet().iterator();
        int removed = 0;

        while (it.hasNext()) {
            Map.Entry<String, ExecutionRecord> entry = it.next();
            ExecutionRecord record = entry.getValue();

            // Só remove execuções completadas/falhas
            if (record.status != ExecutionStatus.RUNNING &&
                record.endTime != null &&
                record.endTime.isBefore(olderThan)) {
                it.remove();
                removed++;
            }
        }

        if (removed > 0) {
            log.debug("Cleanup: {} execuções removidas", removed);
        }
    }

    /**
     * Retorna estatísticas do tracker.
     */
    public TrackerStats getStats() {
        int total = executions.size();
        int running = 0;
        int completed = 0;
        int failed = 0;

        for (ExecutionRecord record : executions.values()) {
            switch (record.status) {
                case RUNNING -> running++;
                case COMPLETED -> completed++;
                case FAILED -> failed++;
            }
        }

        return new TrackerStats(total, running, completed, failed);
    }

    /**
     * Registro de uma execução.
     */
    public static class ExecutionRecord {
        private final ExecutionId executionId;
        private final Instant startTime;
        private final Set<String> children;
        private Instant endTime;
        private ExecutionStatus status;
        private Optional<ToolResult<?>> result;
        private Optional<Throwable> error;

        private ExecutionRecord(ExecutionId executionId) {
            this.executionId = executionId;
            this.startTime = Instant.now();
            this.status = ExecutionStatus.RUNNING;
            this.children = new HashSet<>();
            this.result = Optional.empty();
            this.error = Optional.empty();
        }

        public ExecutionId getExecutionId() {
            return executionId;
        }

        public Instant getStartTime() {
            return startTime;
        }

        public Instant getEndTime() {
            return endTime;
        }

        public ExecutionStatus getStatus() {
            return status;
        }

        public Optional<ToolResult<?>> getResult() {
            return result;
        }

        public Optional<Throwable> getError() {
            return error;
        }

        public long getDurationMillis() {
            return endTime != null
                    ? endTime.toEpochMilli() - startTime.toEpochMilli()
                    : System.currentTimeMillis() - startTime.toEpochMilli();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("executionId", executionId.getId());
            map.put("type", executionId.getType());
            map.put("depth", executionId.getDepth());
            map.put("startTime", startTime.toString());
            map.put("endTime", endTime != null ? endTime.toString() : null);
            map.put("durationMillis", getDurationMillis());
            map.put("status", status);
            map.put("childrenCount", children.size());
            result.ifPresent(r -> map.put("result", r.getData().orElse(null)));
            error.ifPresent(e -> map.put("error", e.getMessage()));
            return map;
        }
    }

    /**
     * Status de uma execução.
     */
    public enum ExecutionStatus {
        RUNNING,
        COMPLETED,
        FAILED
    }

    /**
     * Estatísticas do tracker.
     */
    public record TrackerStats(
            int totalExecutions,
            int running,
            int completed,
            int failed
    ) {
        public double getSuccessRate() {
            int finished = completed + failed;
            return finished > 0 ? (double) completed / finished : 0;
        }
    }
}
