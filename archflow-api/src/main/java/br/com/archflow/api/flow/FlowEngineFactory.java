package br.com.archflow.api.flow;

import br.com.archflow.agent.config.AgentConfig;
import br.com.archflow.agent.execution.DefaultExecutionManager;
import br.com.archflow.agent.execution.DefaultFlowExecutor;
import br.com.archflow.agent.execution.DefaultParallelExecutor;
import br.com.archflow.agent.metrics.MetricsCollector;
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
        return create(flowRepository, 16, 3_600_000L, 8);
    }

    public static FlowEngine create(FlowRepository flowRepository,
                                    int maxConcurrentFlows, long flowTimeoutMs, int maxParallelSteps) {
        ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
        MetricsCollector metrics = new MetricsCollector(AgentConfig.builder().build());

        // Pick up any process-wide registered listeners (e.g. LinktorFlowPublisher).
        CompositeFlowLifecycleListener lifecycle = new CompositeFlowLifecycleListener();
        for (FlowLifecycleListener extra : FlowLifecycleListeners.snapshot()) {
            lifecycle.add(extra);
        }

        DefaultFlowExecutor flowExecutor = new DefaultFlowExecutor(
                Thread.currentThread().getContextClassLoader(), metrics, lifecycle);
        DefaultParallelExecutor parallelExecutor =
                new DefaultParallelExecutor(executorService, maxParallelSteps);
        DefaultExecutionManager executionManager =
                new DefaultExecutionManager(flowExecutor, parallelExecutor, executorService);

        StateManager stateManager = new InMemoryStateManager();

        return new DefaultFlowEngine(
                executionManager,
                flowRepository,
                stateManager,
                new DefaultFlowValidator(),
                null,   // memoryRestorer — tolerated null
                null,   // traceRecorder — tolerated null
                maxConcurrentFlows,
                flowTimeoutMs,
                lifecycle);
    }
}
