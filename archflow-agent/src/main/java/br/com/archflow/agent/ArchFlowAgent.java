package br.com.archflow.agent;

import br.com.archflow.agent.config.AgentConfig;
import br.com.archflow.agent.context.FlowContextBuilder;
import br.com.archflow.agent.execution.DefaultExecutionManager;
import br.com.archflow.agent.execution.DefaultFlowExecutor;
import br.com.archflow.agent.execution.DefaultParallelExecutor;
import br.com.archflow.agent.metrics.MetricsCollector;
import br.com.archflow.agent.persistence.InMemoryFlowRepository;
import br.com.archflow.agent.persistence.InMemoryStateRepository;
import br.com.archflow.agent.plugin.FlowPluginManager;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.agent.streaming.RegistryFlowLifecycleListener;
import br.com.archflow.agent.streaming.RunningFlowsRegistry;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.core.DefaultFlowEngine;
import br.com.archflow.engine.core.ExecutionManager;
import br.com.archflow.engine.core.StateManager;
import br.com.archflow.engine.execution.FlowExecutor;
import br.com.archflow.engine.execution.ParallelExecutor;
import br.com.archflow.engine.lifecycle.FlowLifecycleListener;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.engine.persistence.StateRepository;
import br.com.archflow.engine.validation.DefaultFlowValidator;
import br.com.archflow.engine.validation.FlowValidator;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.flow.*;
import br.com.archflow.model.flow.Flow;

import java.util.Map;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Implementação principal do ArchFlow Agent.
 * Responsável por gerenciar e executar fluxos de trabalho.
 */
public class ArchFlowAgent implements AutoCloseable {
    private static final Logger logger = Logger.getLogger(ArchFlowAgent.class.getName());

    private final AgentConfig config;
    private final FlowPluginManager pluginManager;
    private final MetricsCollector metricsCollector;
    private final StateRepository stateRepository;
    private final FlowRepository flowRepository;
    private final FlowEngine flowEngine;
    private final ExecutorService executorService;
    private final StateManager stateManager;
    private final EventStreamRegistry eventStreamRegistry;
    private final RunningFlowsRegistry runningFlowsRegistry;

    public ArchFlowAgent(AgentConfig config) {
        this.config = config;

        // Inicializa componentes principais
        this.executorService = createExecutorService();
        this.stateRepository = new InMemoryStateRepository();
        this.flowRepository = new InMemoryFlowRepository();
        this.metricsCollector = new MetricsCollector(config);
        this.pluginManager = new FlowPluginManager(config.pluginsPath());
        this.stateManager = createStateManager();

        // Inicializa infraestrutura de streaming + observabilidade
        this.eventStreamRegistry = new EventStreamRegistry();
        this.runningFlowsRegistry = new RunningFlowsRegistry();

        // Inicializa engine de execução
        this.flowEngine = createFlowEngine();

        logger.info("ArchFlow Agent iniciado com configuração: " + config);
    }

    protected StateManager getStateManager() {
        return this.stateManager;
    }

    /**
     * Returns the SSE event stream registry.
     * Used by the protobuf publisher (Fase C) to register a global listener.
     *
     * @return the shared EventStreamRegistry
     */
    public EventStreamRegistry getEventStreamRegistry() {
        return this.eventStreamRegistry;
    }

    /**
     * Returns the running flows registry for observability endpoints.
     *
     * @return the shared RunningFlowsRegistry
     */
    public RunningFlowsRegistry getRunningFlowsRegistry() {
        return this.runningFlowsRegistry;
    }

    /**
     * Executa um fluxo de forma assíncrona.
     *
     * <p>Setup síncrono (metrics start, plugin load, repository save) é
     * executado na thread chamadora — se falhar, devolvemos uma future
     * já concluída excepcionalmente. A execução propriamente dita usa
     * {@link FlowEngine#startFlow} e sua future é retornada diretamente
     * (sem {@code supplyAsync + get} aninhado, que podia levar a
     * bloqueio prolongado sem timeout propagável).</p>
     */
    public CompletableFuture<FlowResult> executeFlow(Flow flow, Map<String, Object> input) {
        String flowId = flow.getId();
        logger.info("Iniciando execução do fluxo: " + flowId);

        try {
            metricsCollector.recordFlowStart(flowId);
            pluginManager.loadPluginsForFlow(flow);
            flowRepository.save(flow);
        } catch (Exception e) {
            logger.severe("Erro preparando fluxo: " + flowId + " - " + e.getMessage());
            metricsCollector.recordFlowError(flowId, e);
            return CompletableFuture.failedFuture(
                    new RuntimeException("Erro executando fluxo: " + flowId, e));
        }

        try {
            return flowEngine.startFlow(flowId, input)
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            logger.severe("Erro executando fluxo " + flowId + ": " + error.getMessage());
                            metricsCollector.recordFlowError(flowId, error);
                        } else if (result != null) {
                            logger.info("Fluxo " + flowId + " concluído com status: " + result.getStatus());
                            metricsCollector.recordFlowCompletion(flowId, result.getMetrics(),
                                    result.getStatus() == ExecutionStatus.COMPLETED);
                        }
                    });
        } catch (Exception e) {
            logger.severe("Erro executando fluxo: " + flowId + " - " + e.getMessage());
            metricsCollector.recordFlowError(flowId, e);
            return CompletableFuture.failedFuture(
                    new RuntimeException("Erro executando fluxo: " + flowId, e));
        }
    }

    /**
     * Para a execução de um fluxo
     */
    public void stopFlow(String flowId) {
        logger.info("Parando fluxo: " + flowId);
        try {
            flowEngine.cancel(flowId);
            metricsCollector.recordFlowStatus(flowId, FlowStatus.STOPPED);
        } catch (Exception e) {
            logger.severe("Erro parando fluxo: " + flowId + " - " + e.getMessage());
            throw new RuntimeException("Erro parando fluxo: " + flowId, e);
        }
    }

    /**
     * Pausa a execução de um fluxo
     */
    public void pauseFlow(String flowId) {
        logger.info("Pausando fluxo: " + flowId);
        try {
            flowEngine.pause(flowId);
            metricsCollector.recordFlowStatus(flowId, FlowStatus.PAUSED);
        } catch (Exception e) {
            logger.severe("Erro pausando fluxo: " + flowId + " - " + e.getMessage());
            throw new RuntimeException("Erro pausando fluxo: " + flowId, e);
        }
    }

    /**
     * Retoma a execução de um fluxo pausado.
     * Ver {@link #executeFlow} para a rationale de evitar
     * {@code supplyAsync + get}.
     */
    public CompletableFuture<FlowResult> resumeFlow(String flowId) {
        logger.info("Retomando fluxo: " + flowId);

        FlowState state;
        Flow flow;
        ExecutionContext context;
        try {
            state = getStateManager().loadState(flowId);
            if (state == null) {
                throw new IllegalStateException("Estado não encontrado para fluxo: " + flowId);
            }

            flow = flowRepository.findById(flowId)
                    .orElseThrow(() -> new IllegalStateException("Fluxo não encontrado: " + flowId));

            context = FlowContextBuilder.forFlow(flow)
                    .withInitialVariables(state.getVariables())
                    .withAdditionalContext(Map.of(
                            "resumeTime", System.currentTimeMillis(),
                            "previousState", state.getStatus()
                    ))
                    .build();
        } catch (Exception e) {
            logger.severe("Erro retomando fluxo: " + flowId + " - " + e.getMessage());
            return CompletableFuture.failedFuture(
                    new RuntimeException("Erro retomando fluxo: " + flowId, e));
        }

        try {
            return flowEngine.resumeFlow(flowId, context)
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            logger.severe("Erro retomando fluxo " + flowId + ": " + error.getMessage());
                            metricsCollector.recordFlowError(flowId, error);
                        } else if (result != null) {
                            logger.info("Fluxo " + flowId + " retomado com status: " + result.getStatus());
                            metricsCollector.recordFlowStatus(flowId, FlowStatus.RUNNING);
                        }
                    });
        } catch (Exception e) {
            logger.severe("Erro retomando fluxo: " + flowId + " - " + e.getMessage());
            return CompletableFuture.failedFuture(
                    new RuntimeException("Erro retomando fluxo: " + flowId, e));
        }
    }

    /**
     * Obtém o status atual de um fluxo
     */
    public FlowStatus getFlowStatus(String flowId) {
        return flowEngine.getFlowStatus(flowId);
    }

    @Override
    public void close() {
        logger.info("Finalizando ArchFlow Agent");

        try {
            // Para execuções ativas
            flowEngine.getActiveFlows().forEach(flowId -> {
                try {
                    stopFlow(flowId);
                } catch (Exception e) {
                    logger.warning("Erro parando fluxo " + flowId + ": " + e.getMessage());
                }
            });

            // Limpa recursos
            executorService.shutdown();
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }

            pluginManager.clearPlugins();
            metricsCollector.close();
            eventStreamRegistry.shutdown();

            logger.info("ArchFlow Agent finalizado com sucesso");

        } catch (Exception e) {
            logger.severe("Erro finalizando ArchFlow Agent: " + e.getMessage());
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private FlowEngine createFlowEngine() {
        // Compose: the streaming/observability listener is always active
        // and additional listeners (e.g. the Linktor flow publisher) are
        // pulled from the process-wide registry at engine construction.
        br.com.archflow.engine.lifecycle.CompositeFlowLifecycleListener composite =
                new br.com.archflow.engine.lifecycle.CompositeFlowLifecycleListener();
        composite.add(new RegistryFlowLifecycleListener(
                eventStreamRegistry, runningFlowsRegistry));
        for (FlowLifecycleListener extra :
                br.com.archflow.engine.lifecycle.FlowLifecycleListeners.snapshot()) {
            composite.add(extra);
        }
        FlowLifecycleListener lifecycleListener = composite;

        ExecutionManager executionManager = new DefaultExecutionManager(
                createFlowExecutor(lifecycleListener),
                createParallelExecutor(),
                executorService
        );

        FlowValidator flowValidator = new DefaultFlowValidator();
        StateManager stateManager = createStateManager();

        return new DefaultFlowEngine(
                executionManager,
                flowRepository,
                stateManager,
                flowValidator,
                null,  // memoryRestorer
                null,  // traceRecorder
                config.maxConcurrentFlows(),
                config.defaultFlowTimeout(),
                lifecycleListener
        );
    }

    private ExecutorService createExecutorService() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    private StateManager createStateManager() {
        return new StateManager() {
            @Override
            public void saveState(String flowId, FlowState state) {
                stateRepository.saveState(flowId, state);
            }

            @Override
            public FlowState loadState(String flowId) {
                return stateRepository.getState(flowId);
            }

            @Override
            public void updateState(String flowId, StateUpdate update) {
                FlowState state = loadState(flowId);
                if (state != null) {
                    update.apply(state);
                    saveState(flowId, state);
                }
            }
        };
    }

    // Métodos protected para facilitar testes e extensões
    protected FlowExecutor createFlowExecutor() {
        return createFlowExecutor(FlowLifecycleListener.NO_OP);
    }

    protected FlowExecutor createFlowExecutor(FlowLifecycleListener lifecycleListener) {
        return new DefaultFlowExecutor(
                pluginManager.getPluginClassLoader(),
                metricsCollector,
                lifecycleListener
        );
    }

    protected ParallelExecutor createParallelExecutor() {
        return new DefaultParallelExecutor(
                executorService,
                config.maxParallelStepsPerFlow()
        );
    }
}