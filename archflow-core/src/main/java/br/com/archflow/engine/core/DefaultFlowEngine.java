package br.com.archflow.engine.core;

import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.exceptions.FlowEngineException;
import br.com.archflow.engine.exceptions.FlowNotFoundException;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.error.ExecutionError;
import br.com.archflow.model.flow.*;
import br.com.archflow.engine.validation.FlowValidator;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class DefaultFlowEngine implements FlowEngine {
    private static final Logger logger = Logger.getLogger(DefaultFlowEngine.class.getName());

    private final ExecutionManager executionManager;
    private final FlowRepository flowRepository;
    private final StateManager stateManager;
    private final FlowValidator flowValidator;
    private final MemoryRestorer memoryRestorer;
    private final Map<String, FlowExecution> activeExecutions;

    public DefaultFlowEngine(ExecutionManager executionManager,
                             FlowRepository flowRepository,
                             StateManager stateManager,
                             FlowValidator flowValidator) {
        this(executionManager, flowRepository, stateManager, flowValidator, null);
    }

    public DefaultFlowEngine(ExecutionManager executionManager,
                             FlowRepository flowRepository,
                             StateManager stateManager,
                             FlowValidator flowValidator,
                             MemoryRestorer memoryRestorer) {
        this.executionManager = executionManager;
        this.flowRepository = flowRepository;
        this.stateManager = stateManager;
        this.flowValidator = flowValidator;
        this.memoryRestorer = memoryRestorer;
        this.activeExecutions = new ConcurrentHashMap<>();
    }

    @Override
    public CompletableFuture<FlowResult> startFlow(String flowId, Map<String, Object> input) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Flow flow = flowRepository.findById(flowId)
                        .orElseThrow(() -> new FlowNotFoundException(flowId));

                flowValidator.validate(flow);

                ExecutionContext context = createInitialContext(flow, input);
                FlowExecution execution = new FlowExecution(flow, context);
                activeExecutions.put(flowId, execution);

                return executionManager.executeFlow(flow, context);
            } catch (Exception e) {
                handleExecutionError(flowId, e);
                throw new FlowEngineException("Error starting flow: " + flowId, e);
            }
        });
    }

    @Override
    public CompletableFuture<FlowResult> execute(Flow flow, ExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                flowValidator.validate(flow);

                if (context.getState() == null) {
                    FlowState initialState = createInitialState(flow.getId(), context.getTenantId());
                    context.setState(initialState);
                }

                FlowExecution execution = new FlowExecution(flow, context);
                activeExecutions.put(flow.getId(), execution);

                return executionManager.executeFlow(flow, context);
            } catch (Exception e) {
                handleExecutionError(flow.getId(), e);
                throw new FlowEngineException("Error executing flow: " + flow.getId(), e);
            }
        });
    }

    @Override
    public CompletableFuture<FlowResult> resumeFlow(String flowId, ExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Flow flow = flowRepository.findById(flowId)
                        .orElseThrow(() -> new FlowNotFoundException(flowId));

                FlowState state = stateManager.loadState(flowId);
                if (state == null) {
                    throw new FlowEngineException("No state found for flow: " + flowId);
                }

                if (state.getStatus().isFinal()) {
                    throw new FlowEngineException("Cannot resume flow in final state: " + state.getStatus());
                }

                // Ao retomar, atualizar status para RUNNING
                if (state.getStatus() == FlowStatus.PAUSED || state.getStatus() == FlowStatus.AWAITING_APPROVAL) {
                    state = FlowState.builder()
                            .tenantId(state.getTenantId())
                            .flowId(state.getFlowId())
                            .status(FlowStatus.RUNNING)
                            .currentStepId(state.getCurrentStepId())
                            .variables(state.getVariables())
                            .executionPaths(state.getExecutionPaths())
                            .metrics(state.getMetrics())
                            .error(state.getError())
                            .build();
                }

                context.setState(state);

                // Restaurar ChatMemory (ex: do Redis) antes de retomar
                if (memoryRestorer != null) {
                    try {
                        memoryRestorer.restore(context);
                        logger.info("Memory restored for flow: " + flowId);
                    } catch (Exception e) {
                        logger.warning("Failed to restore memory for flow " + flowId + ": " + e.getMessage());
                    }
                }

                FlowExecution execution = new FlowExecution(flow, context);
                activeExecutions.put(flowId, execution);

                return executionManager.executeFlow(flow, context);
            } catch (Exception e) {
                handleExecutionError(flowId, e);
                throw new FlowEngineException("Error resuming flow: " + flowId, e);
            }
        });
    }

    @Override
    public FlowStatus getFlowStatus(String flowId) {
        try {
            FlowExecution execution = activeExecutions.get(flowId);
            if (execution != null) {
                return execution.getContext().getState().getStatus();
            }

            FlowState state = stateManager.loadState(flowId);
            if (state == null) {
                throw new FlowNotFoundException(flowId);
            }

            return state.getStatus();
        } catch (Exception e) {
            throw new FlowEngineException("Error getting flow status: " + flowId, e);
        }
    }

    @Override
    public void pause(String flowId) {
        try {
            FlowExecution execution = activeExecutions.get(flowId);
            if (execution == null) {
                throw new FlowNotFoundException(flowId);
            }

            execution.pause();
            stateManager.saveState(flowId, execution.getContext().getState());
            executionManager.pauseFlow(flowId);
        } catch (Exception e) {
            throw new FlowEngineException("Error pausing flow: " + flowId, e);
        }
    }

    @Override
    public void cancel(String flowId) {
        try {
            FlowExecution execution = activeExecutions.get(flowId);
            if (execution == null) {
                throw new FlowNotFoundException(flowId);
            }

            execution.cancel();
            stateManager.saveState(flowId, execution.getContext().getState());
            activeExecutions.remove(flowId);
            executionManager.stopFlow(flowId);
        } catch (Exception e) {
            throw new FlowEngineException("Error canceling flow: " + flowId, e);
        }
    }

    private ExecutionContext createInitialContext(Flow flow, Map<String, Object> input) {
        Map<String, Object> vars = input != null ? new HashMap<>(input) : new HashMap<>();
        String tenantId = vars.containsKey("tenantId") ? String.valueOf(vars.get("tenantId")) : "SYSTEM";
        String userId = vars.containsKey("userId") ? String.valueOf(vars.get("userId")) : null;
        String sessionId = vars.containsKey("sessionId") ? String.valueOf(vars.get("sessionId")) : null;

        ExecutionContext context = new DefaultExecutionContext(
                tenantId, userId, sessionId,
                MessageWindowChatMemory.builder()
                        .maxMessages(100)
                        .build()
        );

        FlowState initialState = FlowState.builder()
                .tenantId(tenantId)
                .flowId(flow.getId())
                .status(FlowStatus.INITIALIZED)
                .variables(vars)
                .executionPaths(new ArrayList<>())
                .metrics(FlowMetrics.builder().build())
                .build();

        context.setState(initialState);
        return context;
    }

    private FlowState createInitialState(String flowId) {
        return createInitialState(flowId, "SYSTEM");
    }

    private FlowState createInitialState(String flowId, String tenantId) {
        return FlowState.builder()
                .tenantId(tenantId)
                .flowId(flowId)
                .status(FlowStatus.INITIALIZED)
                .variables(new HashMap<>())
                .executionPaths(new ArrayList<>())
                .metrics(FlowMetrics.builder().build())
                .build();
    }

    private void handleExecutionError(String flowId, Exception e) {
        try {
            FlowExecution execution = activeExecutions.remove(flowId);
            if (execution != null) {
                FlowState currentState = execution.getContext().getState();

                ExecutionError error = ExecutionError.fromException(
                        "FLOW_EXECUTION_ERROR",
                        e,
                        "FlowEngine"
                );

                FlowState errorState = FlowState.builder()
                        .tenantId(currentState.getTenantId())
                        .flowId(currentState.getFlowId())
                        .status(FlowStatus.FAILED)
                        .currentStepId(currentState.getCurrentStepId())
                        .variables(currentState.getVariables())
                        .executionPaths(currentState.getExecutionPaths())
                        .metrics(currentState.getMetrics())
                        .error(error)
                        .build();

                stateManager.saveState(flowId, errorState);
            }
        } catch (Exception ex) {
            logger.severe("Error handling execution error for flow: " + flowId + " - " + ex.getMessage());
        }
    }

    private static class FlowExecution {
        private final Flow flow;
        private final ExecutionContext context;

        public FlowExecution(Flow flow, ExecutionContext context) {
            this.flow = flow;
            this.context = context;
        }

        public void pause() {
            updateState(FlowStatus.PAUSED);
        }

        public void cancel() {
            updateState(FlowStatus.STOPPED);
        }

        private void updateState(FlowStatus newStatus) {
            FlowState currentState = context.getState();
            FlowState updatedState = FlowState.builder()
                    .tenantId(currentState.getTenantId())
                    .flowId(currentState.getFlowId())
                    .status(newStatus)
                    .currentStepId(currentState.getCurrentStepId())
                    .variables(currentState.getVariables())
                    .executionPaths(currentState.getExecutionPaths())
                    .metrics(currentState.getMetrics())
                    .error(currentState.getError())
                    .build();

            context.setState(updatedState);
        }

        public ExecutionContext getContext() {
            return context;
        }
    }

    @Override
    public String requestApproval(String flowId, String stepId, Object proposal) {
        FlowExecution execution = activeExecutions.get(flowId);
        if (execution == null) {
            throw new FlowNotFoundException(flowId);
        }

        FlowState currentState = execution.getContext().getState();
        FlowState awaitingState = FlowState.builder()
                .tenantId(currentState.getTenantId())
                .flowId(currentState.getFlowId())
                .status(FlowStatus.AWAITING_APPROVAL)
                .currentStepId(stepId != null ? stepId : currentState.getCurrentStepId())
                .variables(currentState.getVariables())
                .executionPaths(currentState.getExecutionPaths())
                .metrics(currentState.getMetrics())
                .error(currentState.getError())
                .build();

        execution.getContext().setState(awaitingState);
        stateManager.saveState(flowId, awaitingState);

        String requestId = java.util.UUID.randomUUID().toString();
        logger.info("Flow " + flowId + " awaiting approval: requestId=" + requestId);
        return requestId;
    }

    @Override
    public CompletableFuture<FlowResult> submitApproval(String flowId, String requestId,
                                                         boolean approved, Object editedPayload) {
        FlowExecution execution = activeExecutions.get(flowId);
        if (execution == null) {
            throw new FlowNotFoundException(flowId);
        }

        FlowState currentState = execution.getContext().getState();
        if (currentState.getStatus() != FlowStatus.AWAITING_APPROVAL) {
            throw new FlowEngineException("Flow is not awaiting approval: " + flowId);
        }

        if (!approved) {
            FlowState rejectedState = FlowState.builder()
                    .tenantId(currentState.getTenantId())
                    .flowId(currentState.getFlowId())
                    .status(FlowStatus.STOPPED)
                    .currentStepId(currentState.getCurrentStepId())
                    .variables(currentState.getVariables())
                    .executionPaths(currentState.getExecutionPaths())
                    .metrics(currentState.getMetrics())
                    .build();
            execution.getContext().setState(rejectedState);
            stateManager.saveState(flowId, rejectedState);
            activeExecutions.remove(flowId);
            executionManager.stopFlow(flowId);
            return CompletableFuture.completedFuture(null);
        }

        // Aprovado — retomar execução
        if (editedPayload != null) {
            execution.getContext().set("approvalPayload", editedPayload);
        }

        return resumeFlow(flowId, execution.getContext());
    }

    @Override
    public Set<String> getActiveFlows() {
        return Collections.unmodifiableSet(new HashSet<>(activeExecutions.keySet()));
    }
}
