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
        logger.fine("Salvando estado do fluxo: " + flowId);
        
        // Faz uma cópia profunda do estado para evitar modificações externas
        FlowState stateCopy = deepCopyState(state);
        states.put(flowId, stateCopy);
        
        // Registra no audit log
        saveAuditLog(flowId, createAuditLog(flowId, state));
    }

    @Override
    public FlowState getState(String flowId) {
        logger.fine("Recuperando estado do fluxo: " + flowId);
        
        FlowState state = states.get(flowId);
        if (state != null) {
            // Retorna uma cópia para evitar modificações externas
            return deepCopyState(state);
        }
        return null;
    }

    @Override
    public void saveAuditLog(String flowId, AuditLog log) {
        logger.fine("Registrando audit log para fluxo: " + flowId);
        auditLogs.computeIfAbsent(flowId, k -> new ArrayList<>())
                 .add(log);
    }

    /**
     * Recupera o histórico de audit logs de um fluxo
     */
    public List<AuditLog> getAuditLogs(String flowId) {
        return new ArrayList<>(auditLogs.getOrDefault(flowId, new ArrayList<>()));
    }

    /**
     * Registra um erro de execução
     */
    public void saveError(String flowId, ExecutionError error) {
        logger.fine("Registrando erro para fluxo: " + flowId);
        errors.computeIfAbsent(flowId, k -> new ArrayList<>())
              .add(error);
    }

    /**
     * Recupera erros de execução de um fluxo
     */
    public List<ExecutionError> getErrors(String flowId) {
        return new ArrayList<>(errors.getOrDefault(flowId, new ArrayList<>()));
    }

    /**
     * Remove todos os dados de um fluxo
     */
    public void clearFlow(String flowId) {
        states.remove(flowId);
        auditLogs.remove(flowId);
        errors.remove(flowId);
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