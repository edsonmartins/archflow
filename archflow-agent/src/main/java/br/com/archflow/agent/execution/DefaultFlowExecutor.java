package br.com.archflow.agent.execution;

import br.com.archflow.engine.execution.ConditionEvaluator;
import br.com.archflow.engine.execution.FlowControl;
import br.com.archflow.engine.execution.FlowExecutor;
import br.com.archflow.engine.lifecycle.FlowLifecycleListener;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.flow.*;
import br.com.archflow.agent.metrics.MetricsCollector;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.engine.ExecutionMetrics;
import br.com.archflow.model.error.ExecutionError;
import br.com.archflow.model.error.ExecutionErrorType;
import br.com.archflow.model.enums.StepStatus;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.metrics.StepMetrics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Executor de fluxos padrão
 */
public class DefaultFlowExecutor implements FlowExecutor {
    private static final Logger logger = Logger.getLogger(DefaultFlowExecutor.class.getName());

    /** Default per-step execution timeout (ms). Prevents a misbehaving
     * plugin from blocking a flow thread indefinitely. */
    private static final long DEFAULT_STEP_TIMEOUT_MS = 10 * 60 * 1000L; // 10 min

    /**
     * Variável de contexto com os ids dos steps já concluídos (COMPLETED ou
     * SKIPPED). É persistida junto com o estado do fluxo e permite ao resume
     * pular steps concluídos em vez de reexecutar o fluxo inteiro.
     */
    public static final String COMPLETED_STEPS_KEY = "__archflow.completedSteps";

    private final ClassLoader pluginClassLoader;
    private final MetricsCollector metricsCollector;
    private final FlowLifecycleListener lifecycleListener;
    private final Map<String, StepExecution> activeExecutions;
    private final long stepTimeoutMs;
    /** Retry por step (null = sem retry, 1 tentativa). */
    private final br.com.archflow.agent.config.RetryConfig retryConfig;
    private final ConditionEvaluator conditionEvaluator = new ConditionEvaluator();

    public DefaultFlowExecutor(ClassLoader pluginClassLoader, MetricsCollector metricsCollector) {
        this(pluginClassLoader, metricsCollector, FlowLifecycleListener.NO_OP, DEFAULT_STEP_TIMEOUT_MS, null);
    }

    public DefaultFlowExecutor(ClassLoader pluginClassLoader, MetricsCollector metricsCollector,
                               FlowLifecycleListener lifecycleListener) {
        this(pluginClassLoader, metricsCollector, lifecycleListener, DEFAULT_STEP_TIMEOUT_MS, null);
    }

    public DefaultFlowExecutor(ClassLoader pluginClassLoader, MetricsCollector metricsCollector,
                               FlowLifecycleListener lifecycleListener, long stepTimeoutMs) {
        this(pluginClassLoader, metricsCollector, lifecycleListener, stepTimeoutMs, null);
    }

    public DefaultFlowExecutor(ClassLoader pluginClassLoader, MetricsCollector metricsCollector,
                               FlowLifecycleListener lifecycleListener, long stepTimeoutMs,
                               br.com.archflow.agent.config.RetryConfig retryConfig) {
        this.pluginClassLoader = pluginClassLoader;
        this.metricsCollector = metricsCollector;
        this.lifecycleListener = lifecycleListener != null ? lifecycleListener : FlowLifecycleListener.NO_OP;
        this.activeExecutions = new ConcurrentHashMap<>();
        this.stepTimeoutMs = stepTimeoutMs > 0 ? stepTimeoutMs : DEFAULT_STEP_TIMEOUT_MS;
        this.retryConfig = retryConfig;
    }

    @Override
    public FlowResult execute(Flow flow, ExecutionContext context) {
        return execute(flow, context, FlowControl.NONE);
    }

    @Override
    public FlowResult execute(Flow flow, ExecutionContext context, FlowControl control) {
        String flowId = flow.getId();
        Thread.currentThread().setContextClassLoader(pluginClassLoader);

        try {
            logger.info("Iniciando execução do fluxo: " + flowId);
            metricsCollector.recordFlowStart(flowId);

            Traversal traversal = traverse(flow, context, control);
            List<StepResult> results = traversal.results;

            return new FlowResult() {
                @Override
                public ExecutionStatus getStatus() {
                    return traversal.status;
                }

                @Override
                public Optional<Object> getOutput() {
                    return results.isEmpty() ? Optional.empty() :
                            results.get(results.size() - 1).getOutput();
                }

                @Override
                public ExecutionMetrics getMetrics() {
                    return context.getMetrics();
                }

                @Override
                public List<ExecutionError> getErrors() {
                    return results.stream()
                            .filter(r -> r.getStatus().isError())
                            .flatMap(r -> r.getErrors().stream())
                            .map(e -> ExecutionError.of(
                                    e.code(),
                                    e.message(),
                                    ExecutionErrorType.EXECUTION,
                                    e.context().toString()
                            ))
                            .toList();
                }
            };

        } catch (Exception e) {
            logger.severe("Erro executando fluxo: " + flowId + " - " + e.getMessage());
            metricsCollector.recordFlowError(flowId, e);
            throw new RuntimeException("Erro executando fluxo: " + flowId, e);
        }
    }

    /** Resultado da travessia: passos executados + status final do fluxo. */
    private record Traversal(List<StepResult> results, ExecutionStatus status) {
    }

    /**
     * Percorre o fluxo. Dois modos:
     *
     * <ul>
     *   <li><b>Grafo</b> (algum step tem conexões): executa a partir dos steps-raiz
     *       (sem conexão de entrada) e propaga pelas conexões de saída — caminho
     *       normal em sucesso/skip, caminho de erro em falha — avaliando a condição
     *       de cada conexão. Cada step executa no máximo uma vez (ciclos não
     *       re-executam).</li>
     *   <li><b>Legado</b> (nenhuma conexão): executa a lista de steps em ordem.</li>
     * </ul>
     *
     * <p>Entre steps o {@link FlowControl} é consultado: stop → CANCELLED,
     * pause → PAUSED (o estado corrente permanece no contexto para resume).
     */
    private Traversal traverse(Flow flow, ExecutionContext context, FlowControl control) {
        List<FlowStep> steps = flow.getSteps() != null ? flow.getSteps() : List.of();
        List<StepResult> results = new ArrayList<>();
        if (steps.isEmpty()) {
            return new Traversal(results, ExecutionStatus.COMPLETED);
        }

        boolean graphMode = steps.stream()
                .anyMatch(s -> s.getConnections() != null && !s.getConnections().isEmpty());

        Map<String, FlowStep> byId = new HashMap<>();
        steps.forEach(s -> byId.put(s.getId(), s));

        Deque<FlowStep> queue = new ArrayDeque<>();
        if (graphMode) {
            Set<String> targeted = new HashSet<>();
            for (FlowStep s : steps) {
                for (StepConnection c : connectionsOf(s)) {
                    targeted.add(c.getTargetId());
                }
            }
            steps.stream().filter(s -> !targeted.contains(s.getId())).forEach(queue::add);
            if (queue.isEmpty()) {
                throw new IllegalStateException(
                        "Fluxo sem step de entrada (todas as conexões formam ciclo): " + flow.getId());
            }
        } else {
            queue.addAll(steps);
        }

        Set<String> executed = new HashSet<>();
        Set<String> completedInPriorRun = readCompletedSteps(context);
        boolean unhandledFailure = false;
        int stepIndex = 0;
        int totalSteps = steps.size();

        while (!queue.isEmpty()) {
            if (control.isStopRequested()) {
                logger.info("Fluxo " + flow.getId() + " cancelado; interrompendo travessia");
                return new Traversal(results, ExecutionStatus.CANCELLED);
            }
            if (control.isPauseRequested()) {
                logger.info("Fluxo " + flow.getId() + " pausado; suspendendo travessia");
                return new Traversal(results, ExecutionStatus.PAUSED);
            }

            FlowStep step = queue.poll();
            if (!executed.add(step.getId())) {
                continue;
            }

            // Resume incremental: step concluído em execução anterior não
            // reexecuta (efeitos colaterais não duplicam); apenas propaga
            // pelos alvos — os outputs restaurados alimentam as condições.
            if (completedInPriorRun.contains(step.getId())) {
                logger.info("Step " + step.getId() + " já concluído em execução anterior; pulando (resume)");
                if (graphMode) {
                    resolveTargets(step, byId, false, context).forEach(queue::add);
                }
                continue;
            }

            // Checkpoint para resume: marca o step corrente no estado antes de executar
            if (context.getState() != null) {
                context.getState().setCurrentStepId(step.getId());
            }

            StepResult result = executeStepWithRetry(step, context, flow, stepIndex++, totalSteps, control);
            results.add(result);

            if (result.getStatus() == StepStatus.COMPLETED || result.getStatus() == StepStatus.SKIPPED) {
                recordCompletedStep(context, step.getId());
            }

            if (!graphMode) {
                if (result.getStatus().isError()) {
                    unhandledFailure = true;
                }
                continue;
            }

            if (result.getStatus() == StepStatus.FAILED) {
                List<FlowStep> errorTargets = resolveTargets(step, byId, true, context);
                if (errorTargets.isEmpty()) {
                    unhandledFailure = true;
                } else {
                    errorTargets.forEach(queue::add);
                }
            } else {
                resolveTargets(step, byId, false, context).forEach(queue::add);
            }
        }

        return new Traversal(results,
                unhandledFailure ? ExecutionStatus.FAILED : ExecutionStatus.COMPLETED);
    }

    private static List<StepConnection> connectionsOf(FlowStep step) {
        return step.getConnections() != null ? step.getConnections() : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Set<String> readCompletedSteps(ExecutionContext context) {
        return context.get(COMPLETED_STEPS_KEY)
                .filter(v -> v instanceof java.util.Collection)
                .map(v -> (Set<String>) ((java.util.Collection<?>) v).stream()
                        .map(String::valueOf)
                        .collect(java.util.stream.Collectors.toCollection(HashSet::new)))
                .orElseGet(HashSet::new);
    }

    private static void recordCompletedStep(ExecutionContext context, String stepId) {
        // Lista (e não Set) para round-trip JSON estável na persistência
        List<String> completed = new ArrayList<>(context.get(COMPLETED_STEPS_KEY)
                .filter(v -> v instanceof java.util.Collection)
                .map(v -> ((java.util.Collection<?>) v).stream().map(String::valueOf).toList())
                .orElse(List.of()));
        if (!completed.contains(stepId)) {
            completed.add(stepId);
        }
        context.set(COMPLETED_STEPS_KEY, completed);
    }

    /**
     * Resolve os steps-alvo das conexões de saída de {@code step}
     * ({@code errorPath} escolhe entre caminho de erro e caminho normal),
     * avaliando a condição de cada conexão contra o contexto.
     */
    private List<FlowStep> resolveTargets(FlowStep step, Map<String, FlowStep> byId,
                                          boolean errorPath, ExecutionContext context) {
        List<FlowStep> targets = new ArrayList<>();
        for (StepConnection conn : connectionsOf(step)) {
            if (conn.isErrorPath() != errorPath) {
                continue;
            }
            // As conexões ficam no step de ORIGEM (sourceId default = próprio id);
            // ignora defensivamente uma conexão gravada no step errado.
            String sourceId = conn.getSourceId();
            if (sourceId != null && !sourceId.isBlank() && !sourceId.equals(step.getId())) {
                continue;
            }
            if (!conditionEvaluator.evaluate(conn.getCondition().orElse(null), context)) {
                continue;
            }
            FlowStep target = byId.get(conn.getTargetId());
            if (target == null) {
                logger.warning("Conexão de " + step.getId() + " aponta para step inexistente: "
                        + conn.getTargetId());
                continue;
            }
            targets.add(target);
        }
        return targets;
    }

    @Override
    public void handleResult(StepResult result) {
        // Interface callers don't carry the flowId; resolution is only safe
        // when the stepId maps to a single active flow.
        handleResult(findScopedKey(result.getStepId()), result);
    }

    private void handleResult(String scopedKey, StepResult result) {
        String stepId = result.getStepId();
        logger.info("Processando resultado do step: " + stepId);

        StepExecution execution = scopedKey != null ? activeExecutions.get(scopedKey) : null;
        if (execution == null) {
            // Truly unknown stepId — either never registered or already
            // fully processed and cleaned up. Fail loudly so the caller
            // sees the mismatch. The idempotency guard below ensures we
            // do NOT reach here for a second legitimate delivery of a
            // step whose execution entry is still present.
            throw new IllegalStateException(
                    "Step execution not registered for stepId=" + stepId
                    + " (scopedKey=" + scopedKey + ")");
        }

        // Idempotency: if the step future's whenComplete fires AND the
        // executor's explicit timeout path also dispatches a result (or
        // a plugin misbehaves and completes twice), we must process
        // exactly once. CAS on a per-step AtomicBoolean gives us that
        // with zero locking.
        if (!execution.markHandled()) {
            logger.fine("Step " + stepId + " already handled; ignoring duplicate result");
            return;
        }

        try {
            // Registra métricas
            if (result.getMetrics() != null) {
                metricsCollector.recordStepMetrics(
                        execution.getFlowId(),
                        stepId,
                        result.getMetrics()
                );
            }

            // Processa resultado baseado no status
            switch (result.getStatus()) {
                case COMPLETED -> handleSuccess(execution, result);
                case FAILED -> handleFailure(execution, result);
                case SKIPPED -> handleSkipped(execution, result);
                default -> logger.warning("Status não tratado: " + result.getStatus());
            }

            // Remove execução ativa
            activeExecutions.remove(scopedKey);

        } catch (Exception e) {
            logger.severe("Erro processando resultado do step " + stepId + ": " + e.getMessage());
            execution.fail(e);
        }
    }

    /**
     * Finds the flow-scoped key for a stepId (format "flowId:stepId").
     * Fails loudly when the same stepId is active in more than one flow —
     * picking an arbitrary match would deliver the result to the wrong flow.
     */
    private String findScopedKey(String stepId) {
        String suffix = ":" + stepId;
        String found = null;
        for (String key : activeExecutions.keySet()) {
            if (key.endsWith(suffix)) {
                if (found != null) {
                    throw new IllegalStateException(
                            "Ambiguous stepId=" + stepId + " active in multiple flows ("
                            + found + ", " + key + "); use the flow-scoped dispatch path");
                }
                found = key;
            }
        }
        return found;
    }

    // A propagação para os próximos steps é responsabilidade exclusiva da
    // travessia em traverse(); estes handlers só registram o resultado no
    // contexto. (A versão anterior também disparava executeSteps daqui, o que
    // duplicava execuções quando combinado com o laço principal.)

    private void handleSuccess(StepExecution execution, StepResult result) {
        logger.info("Step " + result.getStepId() + " concluído com sucesso");
        execution.getContext().set("step." + result.getStepId() + ".output",
                result.getOutput().orElse(null));
    }

    private void handleFailure(StepExecution execution, StepResult result) {
        logger.severe("Step " + result.getStepId() + " falhou: " +
                result.getErrors().stream()
                        .map(StepError::message)
                        .findFirst().orElse("Sem mensagem de erro"));
        execution.getContext().set("step." + result.getStepId() + ".error",
                result.getErrors());
    }

    private void handleSkipped(StepExecution execution, StepResult result) {
        logger.info("Step " + result.getStepId() + " ignorado");
    }

    /**
     * Executa o step honrando o {@code RetryConfig} (item 1.10 do plano):
     * um step FAILED é retentado até {@code maxAttempts} tentativas totais,
     * com backoff exponencial, a menos que pause/cancel seja sinalizado.
     */
    private StepResult executeStepWithRetry(FlowStep step, ExecutionContext context, Flow flow,
                                            int stepIndex, int stepCount, FlowControl control) {
        int maxAttempts = retryConfig != null ? Math.max(1, retryConfig.maxAttempts()) : 1;
        long delayMs = retryConfig != null ? retryConfig.initialDelay() : 0L;

        StepResult result = executeStep(step, context, flow, stepIndex, stepCount);
        for (int attempt = 2; attempt <= maxAttempts && result.getStatus() == StepStatus.FAILED; attempt++) {
            if (control.isStopRequested() || control.isPauseRequested()) {
                break;
            }
            logger.info("Step " + step.getId() + " falhou; retry " + (attempt - 1)
                    + "/" + (maxAttempts - 1) + " em " + delayMs + "ms");
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            delayMs = (long) (delayMs * retryConfig.backoffMultiplier());
            result = executeStep(step, context, flow, stepIndex, stepCount);
        }
        return result;
    }

    private StepResult executeStep(FlowStep step, ExecutionContext context, Flow flow,
                                   int stepIndex, int stepCount) {
        String stepId = step.getId();
        String scopedKey = flow.getId() + ":" + stepId;
        logger.info("Executando step: " + stepId + " (flow: " + flow.getId() + ")");

        long startNs = System.nanoTime();
        safeLifecycle(() -> lifecycleListener.onStepStarted(flow, step, context, stepIndex, stepCount));

        try {
            // Registra execução ativa com key scoped por flow para evitar
            // colisão entre flows concorrentes com step IDs iguais
            StepExecution execution = new StepExecution(flow, step, context);
            activeExecutions.put(scopedKey, execution);

            CompletableFuture<StepResult> stepFuture = step.execute(context)
                    .whenComplete((r, error) -> {
                        if (error != null) {
                            logger.severe("Erro executando step " + stepId + ": " + error.getMessage());
                            handleResult(scopedKey, createErrorResult(step, error));
                        } else {
                            handleResult(scopedKey, r);
                        }
                    });

            StepResult result;
            try {
                result = stepFuture.get(stepTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                // Try to unblock the step; the whenComplete hook above will
                // fire with a CancellationException and clear activeExecutions
                // via handleResult. We return a synthetic error so the flow
                // engine can route to the error path.
                stepFuture.cancel(true);
                long durationMs = (System.nanoTime() - startNs) / 1_000_000;
                safeLifecycle(() -> lifecycleListener.onStepFailed(flow, step, context, te, durationMs));
                return createErrorResult(step, te);
            }

            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            if (result.getStatus() == StepStatus.SKIPPED) {
                safeLifecycle(() -> lifecycleListener.onStepSkipped(flow, step, context));
            } else if (result.getStatus().isError()) {
                Throwable cause = result.getErrors().isEmpty() ? null
                        : new RuntimeException(result.getErrors().get(0).message());
                safeLifecycle(() -> lifecycleListener.onStepFailed(flow, step, context, cause, durationMs));
            } else {
                safeLifecycle(() -> lifecycleListener.onStepCompleted(flow, step, context, durationMs));
            }
            return result;

        } catch (Exception e) {
            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            logger.severe("Erro executando step: " + stepId + " - " + e.getMessage());
            safeLifecycle(() -> lifecycleListener.onStepFailed(flow, step, context, e, durationMs));
            return createErrorResult(step, e);
        }
    }

    private void safeLifecycle(Runnable callback) {
        try {
            callback.run();
        } catch (Exception e) {
            logger.warning("FlowLifecycleListener callback failed (swallowed): " + e.getMessage());
        }
    }

    private StepResult createErrorResult(FlowStep step, Throwable error) {
        return new StepResult() {
            @Override
            public String getStepId() {
                return step.getId();
            }

            @Override
            public StepStatus getStatus() {
                return StepStatus.FAILED;
            }

            @Override
            public Optional<Object> getOutput() {
                return Optional.empty();
            }

            @Override
            public StepMetrics getMetrics() {
                return null;
            }

            @Override
            public List<StepError> getErrors() {
                return List.of(StepError.fromException(error, "STEP_EXECUTION_ERROR"));
            }
        };
    }

    /**
     * Classe interna para manter estado da execução de um step
     */
    private static class StepExecution {
        private final Flow flow;
        private final FlowStep step;
        private final ExecutionContext context;
        private final String flowId;
        private volatile boolean failed;
        /** CAS guard so handleResult runs at most once per step execution. */
        private final java.util.concurrent.atomic.AtomicBoolean handled =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        public StepExecution(Flow flow, FlowStep step, ExecutionContext context) {
            this.flow = flow;
            this.step = step;
            this.context = context;
            this.flowId = context.getState().getFlowId();
        }

        /** @return {@code true} if this call is the first to mark the execution handled */
        public boolean markHandled() {
            return handled.compareAndSet(false, true);
        }

        public Flow getFlow() {
            return flow;
        }

        public FlowStep getStep() {
            return step;
        }

        public ExecutionContext getContext() {
            return context;
        }

        public String getFlowId() {
            return flowId;
        }

        public void fail(Throwable error) {
            this.failed = true;
            context.set("step." + step.getId() + ".error", error);
        }

        public boolean isFailed() {
            return failed;
        }
    }
}