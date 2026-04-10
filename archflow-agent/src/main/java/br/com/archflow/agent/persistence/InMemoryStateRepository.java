package br.com.archflow.agent.persistence;

import br.com.archflow.engine.persistence.StateRepository;
import br.com.archflow.model.flow.AuditLog;
import br.com.archflow.model.flow.ExecutionPath;
import br.com.archflow.model.flow.FlowMetrics;
import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.error.ExecutionError;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.time.Instant;
import java.util.stream.Collectors;

/**
 * Implementação em memória do StateRepository.
 * Útil para desenvolvimento e testes.
 */
public class InMemoryStateRepository implements StateRepository {
    private static final Logger logger = Logger.getLogger(InMemoryStateRepository.class.getName());
    
    private final Map<String, FlowState> states;
    private final Map<String, List<AuditLog>> auditLogs;
    private final Map<String, List<ExecutionError>> errors;

    public InMemoryStateRepository() {
        this.states = new ConcurrentHashMap<>();
        this.auditLogs = new ConcurrentHashMap<>();
        this.errors = new ConcurrentHashMap<>();
    }

    @Override
    public void saveState(String flowId, FlowState state) {
        String tenantId = state.getTenantId() != null ? state.getTenantId() : "SYSTEM";
        saveState(tenantId, flowId, state);
    }

    @Override
    public FlowState getState(String flowId) {
        return getState("SYSTEM", flowId);
    }

    @Override
    public void saveAuditLog(String flowId, AuditLog log) {
        saveAuditLog("SYSTEM", flowId, log);
    }

    @Override
    public void saveState(String tenantId, String flowId, FlowState state) {
        String key = tenantKey(tenantId, flowId);
        logger.fine("Salvando estado do fluxo: " + key);

        FlowState stateCopy = deepCopyState(state);
        states.put(key, stateCopy);

        saveAuditLog(tenantId, flowId, createAuditLog(flowId, state));
    }

    @Override
    public FlowState getState(String tenantId, String flowId) {
        String key = tenantKey(tenantId, flowId);
        logger.fine("Recuperando estado do fluxo: " + key);

        FlowState state = states.get(key);
        if (state != null) {
            return deepCopyState(state);
        }
        return null;
    }

    @Override
    public void saveAuditLog(String tenantId, String flowId, AuditLog log) {
        String key = tenantKey(tenantId, flowId);
        logger.fine("Registrando audit log para fluxo: " + key);
        auditLogs.computeIfAbsent(key, k -> new ArrayList<>())
                 .add(log);
    }

    @Override
    public List<FlowState> getStatesByTenant(String tenantId) {
        String prefix = tenantId + ":";
        return states.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .map(e -> deepCopyState(e.getValue()))
                .toList();
    }

    private String tenantKey(String tenantId, String flowId) {
        return tenantId + ":" + flowId;
    }

    /**
     * Recupera o histórico de audit logs de um fluxo (backward compat — usa tenant SYSTEM).
     */
    public List<AuditLog> getAuditLogs(String flowId) {
        return getAuditLogs("SYSTEM", flowId);
    }

    /**
     * Recupera o histórico de audit logs de um fluxo com isolamento por tenant.
     */
    public List<AuditLog> getAuditLogs(String tenantId, String flowId) {
        String key = tenantKey(tenantId, flowId);
        return new ArrayList<>(auditLogs.getOrDefault(key, new ArrayList<>()));
    }

    /**
     * Registra um erro de execução (backward compat — usa tenant SYSTEM).
     */
    public void saveError(String flowId, ExecutionError error) {
        saveError("SYSTEM", flowId, error);
    }

    /**
     * Registra um erro de execução com isolamento por tenant.
     */
    public void saveError(String tenantId, String flowId, ExecutionError error) {
        String key = tenantKey(tenantId, flowId);
        logger.fine("Registrando erro para fluxo: " + key);
        errors.computeIfAbsent(key, k -> new ArrayList<>())
              .add(error);
    }

    /**
     * Recupera erros de execução de um fluxo (backward compat — usa tenant SYSTEM).
     */
    public List<ExecutionError> getErrors(String flowId) {
        return getErrors("SYSTEM", flowId);
    }

    /**
     * Recupera erros de execução de um fluxo com isolamento por tenant.
     */
    public List<ExecutionError> getErrors(String tenantId, String flowId) {
        String key = tenantKey(tenantId, flowId);
        return new ArrayList<>(errors.getOrDefault(key, new ArrayList<>()));
    }

    /**
     * Remove todos os dados de um fluxo (backward compat — usa tenant SYSTEM).
     */
    public void clearFlow(String flowId) {
        clearFlow("SYSTEM", flowId);
    }

    /**
     * Remove todos os dados de um fluxo com isolamento por tenant.
     */
    public void clearFlow(String tenantId, String flowId) {
        String key = tenantKey(tenantId, flowId);
        states.remove(key);
        auditLogs.remove(key);
        errors.remove(key);
    }

    private AuditLog createAuditLog(String flowId, FlowState state) {
        return AuditLog.builder()
            .flowId(flowId)
            .timestamp(Instant.now())
            .state(deepCopyState(state))
            .build();
    }

    /**
     * Faz uma cópia profunda do estado para garantir imutabilidade
     */
    private FlowState deepCopyState(FlowState state) {
        return FlowState.builder()
            .tenantId(state.getTenantId())
            .flowId(state.getFlowId())
            .status(state.getStatus())
            .currentStepId(state.getCurrentStepId())
            .variables(new HashMap<>(state.getVariables()))
            .executionPaths(deepCopyExecutionPaths(state.getExecutionPaths()))
            .metrics(deepCopyMetrics(state.getMetrics()))
            .error(state.getError() != null ? copyError(state.getError()) : null)
            .build();
    }

    private List<ExecutionPath> deepCopyExecutionPaths(List<ExecutionPath> paths) {
        if (paths == null) return new ArrayList<>();
        return paths.stream()
            .map(path -> ExecutionPath.builder()
                .pathId(path.getPathId())
                .status(path.getStatus())
                .completedSteps(new ArrayList<>(path.getCompletedSteps()))
                .parallelBranches(deepCopyExecutionPaths(path.getParallelBranches()))
                .build())
            .collect(Collectors.toList());
    }

    private FlowMetrics deepCopyMetrics(FlowMetrics metrics) {
        if (metrics == null) return null;
        return FlowMetrics.builder()
            .startTime(metrics.getStartTime())
            .endTime(metrics.getEndTime())
            .stepMetrics(new HashMap<>(metrics.getStepMetrics()))
            .totalSteps(metrics.getTotalSteps())
            .completedSteps(metrics.getCompletedSteps())
            .build();
    }

    private ExecutionError copyError(ExecutionError error) {
        if (error == null) return null;
        return new ExecutionError(
            error.code(),
            error.message(),
            error.type(),
            error.component(),
            error.timestamp(),
            error.cause(),
            new HashMap<>(error.details())
        );
    }
}