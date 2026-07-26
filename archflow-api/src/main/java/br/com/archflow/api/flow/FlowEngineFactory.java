package br.com.archflow.api.flow;

import br.com.archflow.agent.config.AgentConfig;
import br.com.archflow.agent.execution.DefaultExecutionManager;
import br.com.archflow.agent.execution.DefaultFlowExecutor;
import br.com.archflow.agent.execution.DefaultParallelExecutor;
import br.com.archflow.agent.metrics.MetricsCollector;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.agent.streaming.RegistryFlowLifecycleListener;
import br.com.archflow.agent.streaming.RunningFlowsRegistry;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.core.DefaultFlowEngine;
import br.com.archflow.engine.core.StateManager;
import br.com.archflow.engine.core.TraceRecorder;
import br.com.archflow.engine.lifecycle.CompositeFlowLifecycleListener;
import br.com.archflow.engine.lifecycle.FlowLifecycleListener;
import br.com.archflow.engine.lifecycle.FlowLifecycleListeners;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.engine.validation.DefaultFlowValidator;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Builds a ready-to-run {@link FlowEngine} from its collaborators (design-0005
 * step 1), mirroring the canonical wiring in {@code ArchFlowAgent}. Kept as a
 * plain factory (no Spring) so the full graph is unit-testable without a context.
 *
 * <p>A {@link FlowStateChatMemory} restores the conversation on resume (the
 * checkpoint listener captures it into the flow variables); a
 * {@link InMemoryStateManager} backs state/resume for dev.
 *
 * <p>The {@link TraceRecorder} and the {@link MetricsCollector} are parameters of
 * the full overload: without a caller supplying them the engine produces no trace
 * and its metrics stay confined to an instance nobody can read. The shorter
 * overloads keep passing {@code null}/a private collector for tests and dev.
 */
public final class FlowEngineFactory {

    /** Mesmo default do {@link DefaultFlowExecutor}; explicitado aqui porque a
     *  sobrecarga com {@code strictConditions} precisa passar a lista completa. */
    private static final long DEFAULT_STEP_TIMEOUT_MS = 10 * 60 * 1000L;

    private FlowEngineFactory() {}

    public static FlowEngine create(FlowRepository flowRepository) {
        return create(flowRepository, null, null, new InMemoryStateManager(), 16, 3_600_000L, 8);
    }

    /** As {@link #create(FlowRepository)} but streaming flow/step lifecycle to the SSE feed. */
    public static FlowEngine create(FlowRepository flowRepository,
                                    EventStreamRegistry streamRegistry,
                                    RunningFlowsRegistry runningFlows) {
        return create(flowRepository, streamRegistry, runningFlows, new InMemoryStateManager(), 16, 3_600_000L, 8);
    }

    /** As above but with a shared {@link StateManager} (design-0005 step 4). */
    public static FlowEngine create(FlowRepository flowRepository,
                                    EventStreamRegistry streamRegistry,
                                    RunningFlowsRegistry runningFlows,
                                    StateManager stateManager) {
        return create(flowRepository, streamRegistry, runningFlows, stateManager, 16, 3_600_000L, 8);
    }

    public static FlowEngine create(FlowRepository flowRepository,
                                    EventStreamRegistry streamRegistry,
                                    RunningFlowsRegistry runningFlows,
                                    StateManager stateManager,
                                    int maxConcurrentFlows, long flowTimeoutMs, int maxParallelSteps) {
        return create(flowRepository, streamRegistry, runningFlows, stateManager,
                null, null, maxConcurrentFlows, flowTimeoutMs, maxParallelSteps);
    }

    /**
     * Full wiring. {@code metricsCollector} and {@code traceRecorder} may be
     * {@code null} (the engine tolerates both), but a deployment that wants the
     * admin observability surface to show anything must supply them — the trace
     * store has no other writer and the collector no other reader.
     */
    public static FlowEngine create(FlowRepository flowRepository,
                                    EventStreamRegistry streamRegistry,
                                    RunningFlowsRegistry runningFlows,
                                    StateManager stateManager,
                                    MetricsCollector metricsCollector,
                                    TraceRecorder traceRecorder,
                                    int maxConcurrentFlows, long flowTimeoutMs, int maxParallelSteps) {
        return create(flowRepository, streamRegistry, runningFlows, stateManager, metricsCollector,
                traceRecorder, maxConcurrentFlows, flowTimeoutMs, maxParallelSteps, false);
    }

    /**
     * @param strictConditions quando {@code true}, uma condição de transição não
     *                         avaliável BLOQUEIA o caminho em vez de liberá-lo
     */
    public static FlowEngine create(FlowRepository flowRepository,
                                    EventStreamRegistry streamRegistry,
                                    RunningFlowsRegistry runningFlows,
                                    StateManager stateManager,
                                    MetricsCollector metricsCollector,
                                    TraceRecorder traceRecorder,
                                    int maxConcurrentFlows, long flowTimeoutMs, int maxParallelSteps,
                                    boolean strictConditions) {
        ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
        MetricsCollector metrics = metricsCollector != null
                ? metricsCollector
                : new MetricsCollector(AgentConfig.builder().build());

        CompositeFlowLifecycleListener lifecycle = new CompositeFlowLifecycleListener();
        // Stream flow/step lifecycle to the per-tenant SSE feed (design-0005 step 3).
        if (streamRegistry != null && runningFlows != null) {
            lifecycle.add(new RegistryFlowLifecycleListener(streamRegistry, runningFlows));
        }
        // Pick up any process-wide registered listeners (e.g. LinktorFlowPublisher).
        for (FlowLifecycleListener extra : FlowLifecycleListeners.snapshot()) {
            lifecycle.add(extra);
        }

        StateManager effectiveStateManager =
                stateManager != null ? stateManager : new InMemoryStateManager();
        // Checkpoint durável por step (item 1.7): crash no meio do fluxo deixa
        // o último estado no store; o resume incremental retoma de lá.
        lifecycle.add(new CheckpointingLifecycleListener(effectiveStateManager));

        DefaultFlowExecutor flowExecutor = new DefaultFlowExecutor(
                Thread.currentThread().getContextClassLoader(), metrics, lifecycle,
                DEFAULT_STEP_TIMEOUT_MS, null, strictConditions);
        DefaultParallelExecutor parallelExecutor =
                new DefaultParallelExecutor(executorService, maxParallelSteps);
        DefaultExecutionManager executionManager =
                new DefaultExecutionManager(flowExecutor, parallelExecutor, executorService);

        return new DefaultFlowEngine(
                executionManager,
                flowRepository,
                effectiveStateManager,
                new DefaultFlowValidator(),
                // Restaura a conversa que o CheckpointingLifecycleListener
                // capturou nas variáveis — sem isto o resume perde o histórico.
                new FlowStateChatMemory(),
                traceRecorder,
                maxConcurrentFlows,
                flowTimeoutMs,
                lifecycle);
    }
}
