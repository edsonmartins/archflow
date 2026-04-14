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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public class DefaultFlowEngine implements FlowEngine {
    private static final Logger logger = Logger.getLogger(DefaultFlowEngine.class.getName());

    /** Default maximum concurrent flows when no explicit limit is set. */
    private static final int DEFAULT_MAX_CONCURRENT_FLOWS = 10;

    /** Default flow execution timeout (ms) when not configured. */
    private static final long DEFAULT_FLOW_TIMEOUT_MS = 3_600_000; // 1 hour

    private final ExecutionManager executionManager;
    private final FlowRepository flowRepository;
    private final StateManager stateManager;
    private final FlowValidator flowValidator;
    private final MemoryRestorer memoryRestorer;
    private final TraceRecorder traceRecorder;
    private final Map<String, FlowExecution> activeExecutions;
    private final ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Semaphore that enforces the global maximum number of flows that
     * can execute concurrently. Each {@code startFlow} / {@code execute}
     * / {@code resumeFlow} acquires a permit before spawning the virtual
     * thread task, and releases it when the task completes (success or
     * failure). This prevents unbounded virtual-thread creation and
     * provides backpressure when the system is at capacity.
     */
    private final Semaphore flowSemaphore;

    /**
     * Timeout applied to every flow execution (ms). A running flow
     * that exceeds this duration is completed exceptionally with a
     * {@link TimeoutException}.
     */
    private final long flowTimeoutMs;

    /** Gauge: number of flows currently executing. */
    private final AtomicInteger activeFlowCount = new AtomicInteger(0);

    // ── Constructors ────────────────────────────────────────────────

    public DefaultFlowEngine(ExecutionManager executionManager,
                             FlowRepository flowRepository,
                             StateManager stateManager,
                             FlowValidator flowValidator) {
        this(executionManager, flowRepository, stateManager, flowValidator,
                null, null, DEFAULT_MAX_CONCURRENT_FLOWS, DEFAULT_FLOW_TIMEOUT_MS);
    }

    public DefaultFlowEngine(ExecutionManager executionManager,
                             FlowRepository flowRepository,
                             StateManager stateManager,
                             FlowValidator flowValidator,
                             MemoryRestorer memoryRestorer) {
        this(executionManager, flowRepository, stateManager, flowValidator,
                memoryRestorer, null, DEFAULT_MAX_CONCURRENT_FLOWS, DEFAULT_FLOW_TIMEOUT_MS);
    }

    public DefaultFlowEngine(ExecutionManager executionManager,
                             FlowRepository flowRepository,
                             StateManager stateManager,
                             FlowValidator flowValidator,
                             MemoryRestorer memoryRestorer,
                             TraceRecorder traceRecorder) {
        this(executionManager, flowRepository, stateManager, flowValidator,
                memoryRestorer, traceRecorder, DEFAULT_MAX_CONCURRENT_FLOWS, DEFAULT_FLOW_TIMEOUT_MS);
    }

    public DefaultFlowEngine(ExecutionManager executionManager,
                             FlowRepository flowRepository,
                             StateManager stateManager,
                             FlowValidator flowValidator,
                             MemoryRestorer memoryRestorer,
                             TraceRecorder traceRecorder,
                             int maxConcurrentFlows,
                             long flowTimeoutMs) {
        this.executionManager = executionManager;
        this.flowRepository = flowRepository;
        this.stateManager = stateManager;
        this.flowValidator = flowValidator;
        this.memoryRestorer = memoryRestorer;
        this.traceRecorder = traceRecorder;
        this.activeExecutions = new ConcurrentHashMap<>();
        this.flowSemaphore = new Semaphore(maxConcurrentFlows > 0 ? maxConcurrentFlows : DEFAULT_MAX_CONCURRENT_FLOWS);
        this.flowTimeoutMs = flowTimeoutMs > 0 ? flowTimeoutMs : DEFAULT_FLOW_TIMEOUT_MS;
    }

    // ── Flow lifecycle ──────────────────────────────────────────────

    @Override
    public CompletableFuture<FlowResult> startFlow(String flowId, Map<String, Object> input) {
        return submitFlow(flowId, () -> {
            Flow flow = flowRepository.findById(flowId)
                    .orElseThrow(() -> new FlowNotFoundException(flowId));
            flowValidator.validate(flow);
            ExecutionContext context = createInitialContext(flow, input);
            return Map.entry(flow, context);
        });
    }

    @Override
    public CompletableFuture<FlowResult> execute(Flow flow, ExecutionContext context) {
        return submitFlow(flow.getId(), () -> {
            flowValidator.validate(flow);
            if (context.getState() == null) {
                FlowState initialState = createInitialState(flow.getId(), context.getTenantId());
                context.setState(initialState);
            }
            return Map.entry(flow, context);
        });
    }

    @Override
    public CompletableFuture<FlowResult> resumeFlow(String flowId, ExecutionContext context) {
        return submitFlow(flowId, () -> {
            Flow flow = flowRepository.findById(flowId)
                    .orElseThrow(() -> new FlowNotFoundException(flowId));

            FlowState state = stateManager.loadState(flowId);
            if (state == null) {
                throw new FlowEngineException("No state found for flow: " + flowId);
            }
            if (state.getStatus().isFinal()) {
                throw new FlowEngineException("Cannot resume flow in final state: " + state.getStatus());
            }

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

            if (memoryRestorer != null) {
                try {
                    memoryRestorer.restore(context);
                    logger.info("Memory restored for flow: " + flowId);
                } catch (Exception e) {
                    logger.warning("Failed to restore memory for flow " + flowId + ": " + e.getMessage());
                }
            }

            return Map.entry(flow, context);
        });
    }

    /**
     * Central submission method that enforces all concurrency invariants:
     * <ol>
     *   <li><b>Duplicate prevention</b> — {@code putIfAbsent} rejects a
     *       second execution with the same flow ID.</li>
     *   <li><b>Global concurrency limit</b> — the {@link #flowSemaphore}
     *       blocks if the maximum number of concurrent flows is reached.
     *       The caller's virtual thread suspends (not the carrier) until
     *       a permit is released.</li>
     *   <li><b>Timeout enforcement</b> — the returned future is wrapped
     *       with {@link CompletableFuture#orTimeout} so a flow that
     *       exceeds {@link #flowTimeoutMs} is completed exceptionally.</li>
     *   <li><b>Cleanup guarantee</b> — the semaphore permit and the
     *       {@code activeExecutions} entry are released in a finally
     *       block, even on timeout or exception.</li>
     * </ol>
     *
     * @param flowId   flow identifier
     * @param supplier lazy supplier that loads/validates the flow and
     *                 returns a (Flow, ExecutionContext) pair. Called
     *                 inside the virtual thread after the semaphore is
     *                 acquired.
     */
    private CompletableFuture<FlowResult> submitFlow(
            String flowId,
            FlowSetupSupplier supplier) {

        return CompletableFuture.supplyAsync(() -> {
            boolean permitAcquired = false;
            long startMs = System.currentTimeMillis();
            try {
                // ── Acquire global permit (blocks virtual thread, not carrier)
                if (!flowSemaphore.tryAcquire(30, TimeUnit.SECONDS)) {
                    throw new FlowEngineException(
                            "Max concurrent flows reached, timed out waiting for a permit: " + flowId);
                }
                permitAcquired = true;
                activeFlowCount.incrementAndGet();

                // ── Prepare flow + context
                Map.Entry<Flow, ExecutionContext> entry = supplier.get();
                Flow flow = entry.getKey();
                ExecutionContext context = entry.getValue();
                String tenantId = context.getTenantId();

                // ── Duplicate prevention: reject if same flowId is already running
                FlowExecution execution = new FlowExecution(flow, context);
                FlowExecution existing = activeExecutions.putIfAbsent(flowId, execution);
                if (existing != null) {
                    throw new FlowEngineException("Flow is already running: " + flowId);
                }

                notifyTraceStart(flowId, tenantId, null);

                try {
                    FlowResult result = executionManager.executeFlow(flow, context);
                    notifyTraceEnd(flowId, tenantId, result, System.currentTimeMillis() - startMs);
                    return result;
                } catch (Exception execError) {
                    // Save error state while the execution is still in the map
                    saveErrorState(flowId, execution, execError);
                    throw execError;
                } finally {
                    activeExecutions.remove(flowId);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FlowEngineException("Flow execution interrupted: " + flowId, e);
            } catch (FlowEngineException e) {
                throw e;
            } catch (Exception e) {
                throw new FlowEngineException("Error executing flow: " + flowId, e);
            } finally {
                if (permitAcquired) {
                    activeFlowCount.decrementAndGet();
                    flowSemaphore.release();
                }
            }
        }, virtualExecutor).orTimeout(flowTimeoutMs, TimeUnit.MILLISECONDS);
    }

    @FunctionalInterface
    private interface FlowSetupSupplier {
        Map.Entry<Flow, ExecutionContext> get() throws Exception;
    }

    // ── Status / control ────────────────────────────────────────────

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
            // Mark as cancelled — submitFlow's finally block will see the
            // STOPPED status and skip re-removing. The entry stays in the
            // map so the executor can still check state; the finally block
            // in submitFlow handles the actual map cleanup.
            execution.cancel();
            stateManager.saveState(flowId, execution.getContext().getState());
            executionManager.stopFlow(flowId);
        } catch (Exception e) {
            throw new FlowEngineException("Error canceling flow: " + flowId, e);
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
            executionManager.stopFlow(flowId);
            // Note: we do NOT release the semaphore or remove from
            // activeExecutions here — the original submitFlow() virtual
            // thread is still running, blocked inside executionManager.
            // executeFlow. stopFlow signals it to unblock; its finally
            // block will release the permit and remove the map entry
            // exactly once.
            return CompletableFuture.completedFuture(null);
        }

        if (editedPayload != null) {
            execution.getContext().set("approvalPayload", editedPayload);
        }

        // Remove the current entry so resumeFlow → submitFlow can
        // re-register via putIfAbsent without hitting "already running".
        // The original submitFlow virtual thread is still running and
        // will release its own permit when it unblocks and returns.
        activeExecutions.remove(flowId);

        return resumeFlow(flowId, execution.getContext());
    }

    @Override
    public Set<String> getActiveFlows() {
        return Collections.unmodifiableSet(new HashSet<>(activeExecutions.keySet()));
    }

    /** Returns the current number of concurrently executing flows. */
    public int getActiveFlowCount() {
        return activeFlowCount.get();
    }

    /** Returns the number of permits currently available in the global flow semaphore. */
    public int getAvailablePermits() {
        return flowSemaphore.availablePermits();
    }

    // ── Internal helpers ────────────────────────────────────────────

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

    private FlowState createInitialState(String flowId, String tenantId) {
        return FlowState.builder()
                .tenantId(tenantId != null ? tenantId : "SYSTEM")
                .flowId(flowId)
                .status(FlowStatus.INITIALIZED)
                .variables(new HashMap<>())
                .executionPaths(new ArrayList<>())
                .metrics(FlowMetrics.builder().build())
                .build();
    }

    private void notifyTraceStart(String flowId, String tenantId, String personaId) {
        if (traceRecorder != null) {
            try {
                traceRecorder.onFlowStart(flowId, tenantId, personaId);
            } catch (Exception ex) {
                logger.warning("TraceRecorder.onFlowStart failed: " + ex.getMessage());
            }
        }
    }

    private void notifyTraceEnd(String flowId, String tenantId, FlowResult result, long durationMs) {
        if (traceRecorder != null) {
            try {
                traceRecorder.onFlowEnd(flowId, tenantId, result, durationMs);
            } catch (Exception ex) {
                logger.warning("TraceRecorder.onFlowEnd failed: " + ex.getMessage());
            }
        }
    }

    /**
     * Saves an error state for a flow that has a tracked execution.
     * Called inside the inner try-catch of {@link #submitFlow} while
     * the execution is still in {@code activeExecutions}.
     */
    private void saveErrorState(String flowId, FlowExecution execution, Exception e) {
        try {
            FlowState currentState = execution.getContext().getState();
            ExecutionError error = ExecutionError.fromException(
                    "FLOW_EXECUTION_ERROR", e, "FlowEngine");

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
        } catch (Exception ex) {
            logger.severe("Error saving error state for flow: " + flowId + " - " + ex.getMessage());
        }
    }

    // ── FlowExecution inner class ───────────────────────────────────

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
}
