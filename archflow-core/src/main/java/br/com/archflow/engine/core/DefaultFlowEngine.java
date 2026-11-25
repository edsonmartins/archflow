package br.com.archflow.engine.core;

import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.exceptions.FlowEngineException;
import br.com.archflow.engine.exceptions.FlowNotFoundException;
import br.com.archflow.engine.lifecycle.FlowLifecycleListener;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.engine.ExecutionKeys;
import br.com.archflow.model.engine.ExecutionMetrics;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.error.ExecutionError;
import br.com.archflow.model.flow.*;
import br.com.archflow.model.tenant.DefaultTenant;
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

    /** Variável de contexto que guarda o requestId da aprovação pendente. */
    private static final String APPROVAL_REQUEST_KEY = ExecutionKeys.APPROVAL_REQUEST_ID;

    /** Default flow execution timeout (ms) when not configured. */
    private static final long DEFAULT_FLOW_TIMEOUT_MS = 3_600_000; // 1 hour

    private final ExecutionManager executionManager;
    private final FlowRepository flowRepository;
    private final StateManager stateManager;
    private final FlowValidator flowValidator;
    private final MemoryRestorer memoryRestorer;
    private final TraceRecorder traceRecorder;
    private final FlowLifecycleListener lifecycleListener;
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
    private final boolean memoryRestoreFailFast;

    /** Gauge: number of flows currently executing. */
    private final AtomicInteger activeFlowCount = new AtomicInteger(0);

    /**
     * Serializes transition operations (pause/cancel/requestApproval/
     * submitApproval) so check-then-act against {@link #activeExecutions}
     * cannot interleave. Readers (getFlowStatus, startFlow) do not need
     * to acquire this lock.
     */
    private final Object transitionLock = new Object();

    // ── Constructors ────────────────────────────────────────────────

    public DefaultFlowEngine(ExecutionManager executionManager,
                             FlowRepository flowRepository,
                             StateManager stateManager,
                             FlowValidator flowValidator) {
        this(executionManager, flowRepository, stateManager, flowValidator,
                null, null, DEFAULT_MAX_CONCURRENT_FLOWS, DEFAULT_FLOW_TIMEOUT_MS,
                FlowLifecycleListener.NO_OP);
    }

    public DefaultFlowEngine(ExecutionManager executionManager,
                             FlowRepository flowRepository,
                             StateManager stateManager,
                             FlowValidator flowValidator,
                             MemoryRestorer memoryRestorer) {
        this(executionManager, flowRepository, stateManager, flowValidator,
                memoryRestorer, null, DEFAULT_MAX_CONCURRENT_FLOWS, DEFAULT_FLOW_TIMEOUT_MS,
                FlowLifecycleListener.NO_OP);
    }

    public DefaultFlowEngine(ExecutionManager executionManager,
                             FlowRepository flowRepository,
                             StateManager stateManager,
                             FlowValidator flowValidator,
                             MemoryRestorer memoryRestorer,
                             TraceRecorder traceRecorder) {
        this(executionManager, flowRepository, stateManager, flowValidator,
                memoryRestorer, traceRecorder, DEFAULT_MAX_CONCURRENT_FLOWS, DEFAULT_FLOW_TIMEOUT_MS,
                FlowLifecycleListener.NO_OP);
    }

    public DefaultFlowEngine(ExecutionManager executionManager,
                             FlowRepository flowRepository,
                             StateManager stateManager,
                             FlowValidator flowValidator,
                             MemoryRestorer memoryRestorer,
                             TraceRecorder traceRecorder,
                             int maxConcurrentFlows,
                             long flowTimeoutMs) {
        this(executionManager, flowRepository, stateManager, flowValidator,
                memoryRestorer, traceRecorder, maxConcurrentFlows, flowTimeoutMs,
                FlowLifecycleListener.NO_OP);
    }

    /**
     * Full constructor that includes a {@link FlowLifecycleListener}.
     *
     * @param lifecycleListener lifecycle observer; use {@link FlowLifecycleListener#NO_OP} for none
     */
    public DefaultFlowEngine(ExecutionManager executionManager,
                             FlowRepository flowRepository,
                             StateManager stateManager,
                             FlowValidator flowValidator,
                             MemoryRestorer memoryRestorer,
                             TraceRecorder traceRecorder,
                             int maxConcurrentFlows,
                             long flowTimeoutMs,
                             FlowLifecycleListener lifecycleListener) {
        this.executionManager = executionManager;
        this.flowRepository = flowRepository;
        this.stateManager = stateManager;
        this.flowValidator = flowValidator;
        this.memoryRestorer = memoryRestorer;
        this.traceRecorder = traceRecorder;
        this.lifecycleListener = lifecycleListener != null ? lifecycleListener : FlowLifecycleListener.NO_OP;
        this.activeExecutions = new ConcurrentHashMap<>();
        this.flowSemaphore = new Semaphore(maxConcurrentFlows > 0 ? maxConcurrentFlows : DEFAULT_MAX_CONCURRENT_FLOWS);
        this.flowTimeoutMs = flowTimeoutMs > 0 ? flowTimeoutMs : DEFAULT_FLOW_TIMEOUT_MS;
        // Lido uma vez na construção (não a cada resume): falha na restauração
        // de memória aborta o resume por padrão — retomar sem contexto produz
        // respostas erradas em silêncio.
        this.memoryRestoreFailFast = Boolean.parseBoolean(
                System.getProperty("archflow.memory.restore.fail-fast", "true"));
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
            // Um portão à espera só sai por submitApproval, que consome o pedido antes de
            // chamar este método. Retomar com o pedido ainda aberto pulava o portão (ele já
            // está nos passos concluídos): o fluxo concluía sem decisão e sem decisor.
            if (ExecutionKeys.aguardaDecisaoHumana(state)) {
                throw new FlowEngineException("Flow " + flowId
                        + " is awaiting a human decision; submit it through the approval queue");
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

            // Restaura as variáveis persistidas (outputs de steps, checkpoint
            // de concluídos) no contexto — o executor e as condições de
            // transição leem do contexto, não do FlowState.
            if (state.getVariables() != null) {
                state.getVariables().forEach(context::set);
            }

            if (memoryRestorer != null) {
                try {
                    memoryRestorer.restore(context);
                    logger.info("Memory restored for flow: " + flowId);
                } catch (Exception e) {
                    // Resolvido na construção. fail-fast=false (default true)
                    // volta ao comportamento antigo (prosseguir com WARN).
                    if (memoryRestoreFailFast) {
                        throw new IllegalStateException(
                                "Failed to restore memory for flow " + flowId
                                + " — aborting resume (set archflow.memory.restore.fail-fast=false"
                                + " to continue without restored memory)", e);
                    }
                    logger.warning("Failed to restore memory for flow " + flowId
                            + " — continuing WITHOUT restored memory"
                            + " (archflow.memory.restore.fail-fast=false): " + e.getMessage());
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

        CompletableFuture<FlowResult> future = CompletableFuture.supplyAsync(() -> {
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
                int stepCount = flow.getSteps() != null ? flow.getSteps().size() : 0;
                safeLifecycle(() -> lifecycleListener.onFlowStarted(flow, context, stepCount));

                try {
                    FlowResult result = keepApprovalSuspension(
                            flowId, context, executionManager.executeFlow(flow, context));
                    synchronized (transitionLock) {
                        // Decidida enquanto esta travessia ainda desenrolava, a execução já
                        // tem dono: submitApproval gravou a decisão (e, se aprovou, a retomada
                        // escreve neste mesmo contexto). Persistir aqui apagaria quem decidiu
                        // ou reabriria o pedido já consumido.
                        if (!execution.isDecided()) {
                            persistTerminalState(flowId, context, result);
                        }
                    }
                    long durationMs = System.currentTimeMillis() - startMs;
                    notifyTraceEnd(flowId, tenantId, result, durationMs);
                    safeLifecycle(() -> lifecycleListener.onFlowCompleted(flow, context, result, durationMs));
                    return result;
                } catch (Exception execError) {
                    long durationMs = System.currentTimeMillis() - startMs;
                    // Save error state while the execution is still in the map
                    saveErrorState(flowId, execution, execError);
                    // Sem isto o TraceRecorder só enxerga execuções bem-sucedidas —
                    // um painel de traces que nunca mostra falha é pior que nenhum.
                    notifyTraceEnd(flowId, tenantId, failedResult(context, execError), durationMs);
                    safeLifecycle(() -> lifecycleListener.onFlowFailed(flow, context, execError, durationMs));
                    throw execError;
                } finally {
                    // Remove apenas a PRÓPRIA entrada: submitApproval pode já
                    // ter removido esta e registrado a execução do resume sob
                    // o mesmo flowId — um remove(key) puro a apagaria.
                    activeExecutions.remove(flowId, execution);
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

        // orTimeout completa a future, mas a virtual thread continuaria
        // executando o fluxo segurando o permit do semáforo. Sinaliza o
        // cancelamento cooperativo para a travessia parar e o finally da
        // task liberar o permit.
        future.whenComplete((r, err) -> {
            if (err instanceof java.util.concurrent.TimeoutException) {
                try {
                    executionManager.stopFlow(flowId);
                } catch (Exception ex) {
                    logger.warning("Failed to signal stop after flow timeout: " + flowId
                            + " - " + ex.getMessage());
                }
            }
        });
        return future;
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
        // Atomic check+act on the map entry: the lambda runs under the
        // map's bucket lock so a concurrent cancel/submitApproval on the
        // same flowId waits its turn. stateManager/executionManager I/O
        // is intentionally kept out of the lambda to avoid blocking the
        // bucket, which is acceptable here because the state transition
        // (execution.pause()) is idempotent within a single terminal state.
        synchronized (transitionLock) {
            try {
                FlowExecution execution = activeExecutions.computeIfPresent(flowId, (id, e) -> {
                    e.pause();
                    return e;
                });
                if (execution == null) {
                    throw new FlowNotFoundException(flowId);
                }
                syncVariablesToState(execution.getContext());
                stateManager.saveState(flowId, execution.getContext().getState());
                executionManager.pauseFlow(flowId);
            } catch (Exception e) {
                throw new FlowEngineException("Error pausing flow: " + flowId, e);
            }
        }
    }

    @Override
    public void cancel(String flowId) {
        synchronized (transitionLock) {
            try {
                FlowExecution execution = activeExecutions.computeIfPresent(flowId, (id, e) -> {
                    e.cancel();
                    return e;
                });
                if (execution == null) {
                    throw new FlowNotFoundException(flowId);
                }
                // Mark as cancelled — submitFlow's finally block will see the
                // STOPPED status and skip re-removing. The entry stays in the
                // map so the executor can still check state; the finally block
                // in submitFlow handles the actual map cleanup.
                syncVariablesToState(execution.getContext());
                stateManager.saveState(flowId, execution.getContext().getState());
                executionManager.stopFlow(flowId);
            } catch (Exception e) {
                throw new FlowEngineException("Error canceling flow: " + flowId, e);
            }
        }
    }

    @Override
    public String requestApproval(String flowId, String stepId, Object proposal) {
        synchronized (transitionLock) {
            FlowExecution execution = activeExecutions.get(flowId);
            if (execution == null) {
                throw new FlowNotFoundException(flowId);
            }

            String requestId = java.util.UUID.randomUUID().toString();
            // Um portão anterior do mesmo fluxo deixou a decisão dele nas variáveis.
            // Ela não vale para este pedido: sem limpar, o pedido novo apareceria
            // na fila já "decidido" por quem decidiu o anterior (RCHFLW-47).
            clearPreviousGateDecision(execution.getContext());
            // O requestId vai para as variáveis (persistidas com o estado)
            // para ser validado em submitApproval.
            execution.getContext().set(APPROVAL_REQUEST_KEY, requestId);
            // A proposta era recebida e descartada: o humano ficava sem NADA para
            // avaliar. Vai junto do requestId, nas variáveis persistidas, para que
            // a fila de aprovações sobreviva ao restart com o payload intacto.
            if (proposal != null) {
                execution.getContext().set(ExecutionKeys.APPROVAL_PROPOSAL, proposal);
            } else if (execution.getContext().get(ExecutionKeys.APPROVAL_PROPOSAL).isPresent()) {
                // Sem proposta própria, este pedido não pode exibir a do portão anterior.
                execution.getContext().set(ExecutionKeys.APPROVAL_PROPOSAL, "");
            }
            if (stepId != null) {
                execution.getContext().set(ExecutionKeys.APPROVAL_STEP_ID, stepId);
            }
            execution.getContext().set(
                    ExecutionKeys.APPROVAL_REQUESTED_AT, java.time.Instant.now().toString());
            syncVariablesToState(execution.getContext());

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

            // Suspende a travessia de fato (cooperativo): sem isso a thread
            // do fluxo continuaria executando os próximos steps sem esperar
            // a aprovação.
            executionManager.pauseFlow(flowId);

            logger.info("Flow " + flowId + " awaiting approval: requestId=" + requestId);
            return requestId;
        }
    }

    @Override
    public CompletableFuture<FlowResult> submitApproval(String flowId, String requestId,
                                                         boolean approved, Object editedPayload) {
        return submitApproval(flowId, requestId, approved, editedPayload, null, null);
    }

    @Override
    public CompletableFuture<FlowResult> submitApproval(String flowId, String requestId,
                                                        boolean approved, Object editedPayload,
                                                        String decidedBy, String comment) {
        ExecutionContext contextForResume;
        // The entire validate+persist sequence runs under transitionLock so
        // concurrent pause/cancel/submitApproval observe a consistent view.
        // resumeFlow (which re-registers via putIfAbsent) must also run
        // under the lock so a concurrent pause between our remove() and
        // resumeFlow's register does not see a phantom-missing flow.
        synchronized (transitionLock) {
            // A suspensão cooperativa (AWAITING_APPROVAL) desenrola a travessia
            // e remove o fluxo de activeExecutions — então, quando o humano
            // decide, o fluxo normalmente NÃO está mais vivo em memória. A fonte
            // de verdade é o estado persistido; a entrada viva, quando existe
            // (ex.: mock que bloqueia em testes), é apenas um atalho.
            FlowExecution execution = activeExecutions.get(flowId);
            ExecutionContext liveContext = execution != null ? execution.getContext() : null;
            // Com a travessia ainda viva, ler, validar e gravar a decisão acontece sob o monitor
            // do contexto — o mesmo do checkpoint por passo (CheckpointingLifecycleListener). Sem
            // ele, um checkpoint que já tinha lido o estado AWAITING_APPROVAL o regravava por cima
            // da decisão: o pedido recusado voltava à fila, decidível de novo, e o estado durável
            // perdia quem o recusou. Sem travessia viva não há checkpoint concorrente.
            Object checkpointLock = liveContext != null ? liveContext : new Object();
            synchronized (checkpointLock) {
                FlowState currentState = liveContext != null
                        ? liveContext.getState()
                        : stateManager.loadState(flowId);
                if (currentState == null) {
                    throw new FlowNotFoundException(flowId);
                }

                if (currentState.getStatus() != FlowStatus.AWAITING_APPROVAL) {
                    throw new FlowEngineException("Flow is not awaiting approval: " + flowId);
                }

                // Valida o requestId emitido por requestApproval — uma aprovação
                // com id de outra solicitação (ou nenhuma) é rejeitada. O id vive
                // nas variáveis do contexto (fluxo vivo) ou do estado persistido.
                Object storedRequestId = liveContext != null
                        ? liveContext.get(APPROVAL_REQUEST_KEY).orElse(null)
                        : variableOf(currentState, APPROVAL_REQUEST_KEY);
                if (storedRequestId == null || String.valueOf(storedRequestId).isBlank()
                        || !storedRequestId.equals(requestId)) {
                    throw new FlowEngineException("Unknown approval requestId for flow " + flowId
                            + ": " + requestId);
                }

                if (!approved) {
                    Map<String, Object> rejectedVars = new HashMap<>(
                            currentState.getVariables() != null ? currentState.getVariables() : Map.of());
                    rejectedVars.put(APPROVAL_REQUEST_KEY, "");
                    rejectedVars.put(ExecutionKeys.APPROVAL_DECISION, "REJECTED");
                    recordDecider(rejectedVars, requestId, "REJECTED", decidedBy, comment);
                    FlowState rejectedState = rebuildState(currentState, FlowStatus.STOPPED, rejectedVars);
                    stateManager.saveState(flowId, rejectedState);
                    if (liveContext != null) {
                        liveContext.setState(rejectedState);
                        decideLive(execution, rejectedVars);
                        // Sinaliza a thread original (ainda em executeFlow) para
                        // desbloquear; seu finally libera o permit e remove a
                        // entrada do mapa exatamente uma vez.
                        executionManager.stopFlow(flowId);
                    }
                    return CompletableFuture.completedFuture(null);
                }

                // A retomada busca o fluxo no FlowRepository. Sem ele, consumir o pedido deixava a
                // execução presa em AWAITING_APPROVAL sem pedido — fora da fila, sem como decidir de
                // novo, sem virar FAILED — e a falha só existia numa future que ninguém olhava
                // (RCHFLW-59: execuções do editor não registravam o fluxo). Recusar ANTES de consumir
                // mantém o pedido na fila e devolve o erro a quem decidiu. A rejeição acima não
                // precisa da definição: ela só encerra.
                if (flowRepository.findById(flowId).isEmpty()) {
                    throw new FlowEngineException("Flow definition " + flowId + " is not registered, so it "
                            + "cannot be resumed; the approval " + requestId + " stays pending");
                }

                // Consome o requestId e grava a decisão NO STORE (o mapa de
                // variáveis não aceita null). Persistir a consumação sob o lock
                // fecha a janela assíncrona entre remove()/resume e o putIfAbsent
                // do submitFlow: uma submissão duplicada nessa janela recarrega o
                // estado, vê o requestId já consumido e é rejeitada acima.
                Map<String, Object> vars = new HashMap<>(
                        currentState.getVariables() != null ? currentState.getVariables() : Map.of());
                vars.put(APPROVAL_REQUEST_KEY, "");
                // A decisão fica no estado para os steps seguintes ramificarem
                // (`${__archflow.approvalDecision} == APPROVED`) e para a auditoria.
                vars.put(ExecutionKeys.APPROVAL_DECISION, "APPROVED");
                recordDecider(vars, requestId, "APPROVED", decidedBy, comment);
                if (editedPayload != null) {
                    vars.put(ExecutionKeys.APPROVAL_PAYLOAD, editedPayload);
                } else {
                    // O payload é a edição DESTA decisão. Sem edição não há payload — o
                    // que estiver aqui é do portão anterior (ou o marcador vazio que
                    // requestApproval deixou) e os steps seguintes o leriam como deste.
                    vars.remove(ExecutionKeys.APPROVAL_PAYLOAD);
                }
                // Mantém AWAITING_APPROVAL para que resumeFlow faça a transição
                // padrão AWAITING_APPROVAL → RUNNING e restaure as variáveis.
                FlowState consumedState = rebuildState(currentState, FlowStatus.AWAITING_APPROVAL, vars);
                stateManager.saveState(flowId, consumedState);

                if (liveContext != null) {
                    // Remove a entrada viva para que resumeFlow → submitFlow possa
                    // re-registrar via putIfAbsent sem esbarrar em "already running".
                    liveContext.setState(consumedState);
                    decideLive(execution, vars);
                    activeExecutions.remove(flowId);
                    contextForResume = liveContext;
                } else {
                    // Fluxo reidratado do store: contexto novo; resumeFlow recarrega
                    // estado e variáveis a partir do StateManager.
                    contextForResume = createResumeContext(consumedState);
                }
            }
            CompletableFuture<FlowResult> resumed = resumeFlow(flowId, contextForResume);
            // A falha da retomada não pode ficar só na future: quem decidiu pela fila em geral
            // já recebeu a resposta, e ninguém mais a observa.
            resumed.whenComplete((ignored, err) -> {
                if (err != null) {
                    failConsumedApproval(flowId, requestId, err);
                }
            });
            return resumed;
        }
    }

    /**
     * A retomada depois de uma aprovação falhou antes de a travessia começar: a montagem da
     * retomada (repositório, estado, restauração de memória, limite de concorrência) roda fora
     * do try que chama {@code saveErrorState}. O pedido já foi consumido, então o estado ficaria
     * em AWAITING_APPROVAL sem pedido para sempre — fora da fila, sem como decidir de novo e sem
     * nunca virar FAILED (RCHFLW-59). Grava FAILED com o erro.
     *
     * <p>Só quando o estado ainda é exatamente o que a aprovação deixou e nenhuma travessia está
     * viva: uma falha no meio da travessia já foi gravada por {@code saveErrorState}, e outra
     * execução ativa sob o mesmo id é dona do estado. A decisão e o decisor continuam nas
     * variáveis — falhar a retomada não apaga quem autorizou.</p>
     */
    private void failConsumedApproval(String flowId, String requestId, Throwable err) {
        Throwable cause = err;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        synchronized (transitionLock) {
            try {
                if (activeExecutions.containsKey(flowId)) {
                    return;
                }
                FlowState state = stateManager.loadState(flowId);
                if (state == null || state.getStatus() != FlowStatus.AWAITING_APPROVAL
                        || ExecutionKeys.aguardaDecisaoHumana(state)) {
                    return;
                }
                FlowState failed = FlowState.builder()
                        .tenantId(state.getTenantId())
                        .flowId(state.getFlowId())
                        .status(FlowStatus.FAILED)
                        .currentStepId(state.getCurrentStepId())
                        .variables(state.getVariables())
                        .executionPaths(state.getExecutionPaths())
                        .metrics(state.getMetrics())
                        .error(ExecutionError.fromException("APPROVAL_RESUME_ERROR", cause, "FlowEngine"))
                        .build();
                stateManager.saveState(flowId, failed);
                logger.severe("Flow " + flowId + " approved (requestId=" + requestId
                        + ") but could not be resumed; marked FAILED: " + cause);
            } catch (Exception e) {
                logger.severe("Error saving failed resume state for flow " + flowId + ": " + e.getMessage());
            }
        }
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

    /**
     * Grava a identidade do decisor junto da decisão, nas mesmas variáveis e sob
     * o mesmo lock — um log paralelo poderia divergir do estado persistido, e é
     * o estado que responde "quem autorizou isto".
     */
    private static void recordDecider(Map<String, Object> vars, String requestId, String decision,
                                      String decidedBy, String comment) {
        String decidedAt = java.time.Instant.now().toString();
        // O mapa de variáveis vai para JSON e não aceita null; ausente vira "".
        String by = decidedBy != null ? decidedBy : "";
        String why = comment != null ? comment : "";
        vars.put(ExecutionKeys.APPROVAL_DECIDED_AT, decidedAt);
        vars.put(ExecutionKeys.APPROVAL_DECIDED_BY, by);
        vars.put(ExecutionKeys.APPROVAL_COMMENT, why);

        // As chaves acima descrevem só o portão corrente e são limpas no próximo
        // requestApproval. Sem o histórico, um fluxo com dois portões perderia do
        // estado durável quem autorizou o primeiro assim que o segundo pedisse.
        List<Object> history = new ArrayList<>();
        if (vars.get(ExecutionKeys.APPROVAL_HISTORY) instanceof java.util.Collection<?> previous) {
            history.addAll(previous);
        }
        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        Object stepId = vars.get(ExecutionKeys.APPROVAL_STEP_ID);
        entry.put("stepId", stepId != null ? String.valueOf(stepId) : "");
        entry.put("requestId", requestId != null ? requestId : "");
        entry.put("decision", decision);
        entry.put("decidedBy", by);
        entry.put("comment", why);
        entry.put("decidedAt", decidedAt);
        history.add(entry);
        vars.put(ExecutionKeys.APPROVAL_HISTORY, history);
    }

    /**
     * Apaga a decisão do portão anterior antes de abrir um novo pedido. O mapa de
     * variáveis do contexto não aceita remoção nem null, então o marcador é "" —
     * a mesma convenção de {@link ExecutionKeys#APPROVAL_REQUEST_ID} consumido. Só
     * toca chaves presentes: um fluxo de portão único não ganha chaves novas. A
     * auditoria do portão anterior continua em {@link ExecutionKeys#APPROVAL_HISTORY}.
     */
    private static void clearPreviousGateDecision(ExecutionContext context) {
        for (String key : List.of(ExecutionKeys.APPROVAL_DECISION, ExecutionKeys.APPROVAL_DECIDED_BY,
                ExecutionKeys.APPROVAL_COMMENT, ExecutionKeys.APPROVAL_DECIDED_AT,
                ExecutionKeys.APPROVAL_PAYLOAD)) {
            if (context.get(key).isPresent()) {
                context.set(key, "");
            }
        }
    }

    /**
     * Decisão tomada enquanto a travessia original ainda está em {@code executeFlow}
     * (o humano — ou um cliente automático — decidiu entre o pedido e o desenrolar).
     *
     * <p>A decisão vai também para as variáveis do contexto vivo, não só para o estado:
     * a thread original ainda pode copiar o contexto para o estado (checkpoint do
     * próprio portão) e, sem isto, gravaria por cima as variáveis antigas — sem
     * {@code REJECTED}, sem decisor e com o pedido reaberto. E a execução é marcada
     * decidida para o estado terminal dela não sobrescrever o desta decisão.</p>
     */
    private static void decideLive(FlowExecution execution, Map<String, Object> decidedVars) {
        ExecutionContext live = execution.getContext();
        for (String key : List.of(APPROVAL_REQUEST_KEY, ExecutionKeys.APPROVAL_DECISION,
                ExecutionKeys.APPROVAL_DECIDED_BY, ExecutionKeys.APPROVAL_COMMENT,
                ExecutionKeys.APPROVAL_DECIDED_AT, ExecutionKeys.APPROVAL_HISTORY)) {
            Object value = decidedVars.get(key);
            if (value != null) {
                live.set(key, value);
            }
        }
        Object payload = decidedVars.get(ExecutionKeys.APPROVAL_PAYLOAD);
        if (payload != null) {
            live.set(ExecutionKeys.APPROVAL_PAYLOAD, payload);
        } else if (live.get(ExecutionKeys.APPROVAL_PAYLOAD).isPresent()) {
            live.set(ExecutionKeys.APPROVAL_PAYLOAD, "");
        }
        execution.markDecided();
    }

    /**
     * Um fluxo que pediu aprovação não termina COMPLETED. Se o executor devolver
     * COMPLETED com o estado em AWAITING_APPROVAL — o pedido saiu do último step e
     * a travessia não tinha próximo step para notar a pausa —, o resultado vira
     * PAUSED. Sem isto o estado terminal sobrescrevia AWAITING_APPROVAL, o pedido
     * sumia da fila (que filtra por status) e não sobrava nada para retomar
     * (RCHFLW-14). O {@code DefaultFlowExecutor} já trata o caso; esta guarda torna
     * a invariante do motor independente do executor plugado.
     */
    private static FlowResult keepApprovalSuspension(String flowId, ExecutionContext context,
                                                     FlowResult result) {
        if (result == null || result.getStatus() != ExecutionStatus.COMPLETED
                || context.getState() == null
                || context.getState().getStatus() != FlowStatus.AWAITING_APPROVAL) {
            return result;
        }
        logger.warning("Executor devolveu COMPLETED para o fluxo " + flowId
                + " aguardando aprovação; mantendo a suspensão (PAUSED)");
        return new FlowResult() {
            @Override
            public ExecutionStatus getStatus() {
                return ExecutionStatus.PAUSED;
            }

            @Override
            public Optional<Object> getOutput() {
                return result.getOutput();
            }

            @Override
            public ExecutionMetrics getMetrics() {
                return result.getMetrics();
            }

            @Override
            public List<ExecutionError> getErrors() {
                return result.getErrors();
            }
        };
    }

    private static Object variableOf(FlowState state, String key) {
        Map<String, Object> vars = state.getVariables();
        return vars != null ? vars.get(key) : null;
    }

    /** Cópia de {@code base} com novo status e variáveis, preservando o resto. */
    private static FlowState rebuildState(FlowState base, FlowStatus status,
                                          Map<String, Object> variables) {
        return FlowState.builder()
                .tenantId(base.getTenantId())
                .flowId(base.getFlowId())
                .status(status)
                .currentStepId(base.getCurrentStepId())
                .variables(variables)
                .executionPaths(base.getExecutionPaths())
                .metrics(base.getMetrics())
                .error(base.getError())
                .build();
    }

    /**
     * Contexto novo para retomar um fluxo reidratado do {@link StateManager}
     * (sem entrada viva em {@code activeExecutions}). {@code resumeFlow} recarrega
     * estado e variáveis do store; aqui só reconstruímos a identidade
     * (tenant/user/session) e a memória de chat.
     */
    private ExecutionContext createResumeContext(FlowState state) {
        String tenantId = DefaultTenant.orDefault(state.getTenantId());
        Object userId = variableOf(state, "userId");
        Object sessionId = variableOf(state, "sessionId");
        return new DefaultExecutionContext(
                tenantId,
                userId != null ? String.valueOf(userId) : null,
                sessionId != null ? String.valueOf(sessionId) : null,
                MessageWindowChatMemory.builder()
                        .maxMessages(100)
                        .build()
        );
    }

    private ExecutionContext createInitialContext(Flow flow, Map<String, Object> input) {
        // Mesma fronteira da entrada HTTP: a decisão e o histórico de aprovação são do motor,
        // não de quem dispara a execução.
        Map<String, Object> vars = new HashMap<>(ExecutionKeys.semChavesDoMotor(input));
        String tenantId = vars.containsKey("tenantId") ? String.valueOf(vars.get("tenantId")) : DefaultTenant.ID;
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
                // Mesmo padrão do contexto e da requisição HTTP: um estado sem tenant vai para o
                // tenant padrão canônico, onde a fila de aprovações desse tenant o encontra.
                .tenantId(DefaultTenant.orDefault(tenantId))
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
     * {@link FlowResult} sintético para o caminho de exceção, onde o executor
     * não chegou a produzir um. Existe apenas para dar ao {@link TraceRecorder}
     * o mesmo contrato que ele recebe no caminho de sucesso.
     */
    private static FlowResult failedResult(ExecutionContext context, Exception error) {
        List<ExecutionError> errors = List.of(
                ExecutionError.fromException("FLOW_EXECUTION_ERROR", error, "FlowEngine"));
        return new FlowResult() {
            @Override
            public ExecutionStatus getStatus() {
                return ExecutionStatus.FAILED;
            }

            @Override
            public Optional<Object> getOutput() {
                return Optional.empty();
            }

            @Override
            public ExecutionMetrics getMetrics() {
                return context != null ? context.getMetrics() : null;
            }

            @Override
            public List<ExecutionError> getErrors() {
                return errors;
            }
        };
    }

    /**
     * Invokes a lifecycle callback, swallowing any exception to ensure
     * monitoring failures never affect the execution path.
     */
    private void safeLifecycle(Runnable callback) {
        try {
            callback.run();
        } catch (Exception e) {
            logger.warning("FlowLifecycleListener callback failed (swallowed): " + e.getMessage());
        }
    }

    /**
     * Saves an error state for a flow that has a tracked execution.
     * Called inside the inner try-catch of {@link #submitFlow} while
     * the execution is still in {@code activeExecutions}.
     */
    /**
     * Persiste o estado terminal do fluxo após a execução (sucesso, falha
     * tratada, cancelamento ou pausa). Sem isso a persistência durável só
     * veria estados de pause/cancel/approval — após um restart, execuções
     * concluídas "desapareceriam" e getFlowStatus lançaria FlowNotFound.
     * Falha de persistência não derruba o fluxo: loga e segue.
     */
    private void persistTerminalState(String flowId, ExecutionContext context, FlowResult result) {
        try {
            FlowState state = context.getState();
            if (state == null || result == null) {
                return;
            }
            switch (result.getStatus()) {
                case COMPLETED -> state.setStatus(FlowStatus.COMPLETED);
                case FAILED -> state.setStatus(FlowStatus.FAILED);
                case CANCELLED -> state.setStatus(FlowStatus.STOPPED);
                // Se um requestApproval marcou AWAITING_APPROVAL, a suspensão
                // cooperativa devolve PAUSED — preserva o status mais específico.
                case PAUSED -> {
                    if (state.getStatus() != FlowStatus.AWAITING_APPROVAL) {
                        state.setStatus(FlowStatus.PAUSED);
                    }
                }
                default -> { return; }
            }
            syncVariablesToState(context);
            stateManager.saveState(flowId, state);
        } catch (Exception ex) {
            logger.severe("Error saving terminal state for flow: " + flowId + " - " + ex.getMessage());
        }
    }

    /**
     * Copia as variáveis do contexto (onde o executor grava outputs e o
     * checkpoint de steps concluídos) para o FlowState antes de persistir.
     * Sem isso o estado durável não carrega os dados necessários ao resume.
     */
    private void syncVariablesToState(ExecutionContext context) {
        FlowState state = context.getState();
        if (state != null) {
            // Mesma regra do checkpoint por passo: infraestrutura da execução nao vai para o
            // estado durável. Os dois caminhos filtram pelo mesmo lugar de propósito — foi um
            // deles sozinho que deixou o bean passar.
            state.setVariables(ExecutionKeys.persistiveis(context.getVariables()));
        }
    }

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
        /** Uma decisão humana chegou enquanto esta travessia ainda estava viva. */
        private volatile boolean decided;

        public FlowExecution(Flow flow, ExecutionContext context) {
            this.flow = flow;
            this.context = context;
        }

        void markDecided() {
            decided = true;
        }

        boolean isDecided() {
            return decided;
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
