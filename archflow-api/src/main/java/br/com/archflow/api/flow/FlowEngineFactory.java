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
 * <p>{@code memoryRestorer} and {@code traceRecorder} are passed as {@code null}
 * (the engine tolerates both, as ArchFlowAgent does); a {@link InMemoryStateManager}
 * backs state/resume for dev.
 */
public final class FlowEngineFactory {

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
        ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
        MetricsCollector metrics = new MetricsCollector(AgentConfig.builder().build());

        CompositeFlowLifecycleListener lifecycle = new CompositeFlowLifecycleListener();
        // Stream flow/step lifecycle to the per-tenant SSE feed (design-0005 step 3).
        if (streamRegistry != null && runningFlows != null) {
            lifecycle.add(new RegistryFlowLifecycleListener(streamRegistry, runningFlows));
        }
        // Pick up any process-wide registered listeners (e.g. LinktorFlowPublisher).
        for (FlowLifecycleListener extra : FlowLifecycleListeners.snapshot()) {
            lifecycle.add(extra);
        }

        DefaultFlowExecutor flowExecutor = new DefaultFlowExecutor(
                Thread.currentThread().getContextClassLoader(), metrics, lifecycle);
        DefaultParallelExecutor parallelExecutor =
                new DefaultParallelExecutor(executorService, maxParallelSteps);
        DefaultExecutionManager executionManager =
                new DefaultExecutionManager(flowExecutor, parallelExecutor, executorService);

        return new DefaultFlowEngine(
                executionManager,
                flowRepository,
                stateManager != null ? stateManager : new InMemoryStateManager(),
                new DefaultFlowValidator(),
                null,   // memoryRestorer — tolerated null
                null,   // traceRecorder — tolerated null
                maxConcurrentFlows,
                flowTimeoutMs,
                lifecycle);
    }
}
