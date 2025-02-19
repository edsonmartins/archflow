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
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.core.DefaultFlowEngine;
import br.com.archflow.engine.core.ExecutionManager;
import br.com.archflow.engine.core.StateManager;
import br.com.archflow.engine.execution.FlowExecutor;
import br.com.archflow.engine.execution.ParallelExecutor;
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

    public ArchFlowAgent(AgentConfig config) {
        this.config = config;

        // Inicializa componentes principais
        this.executorService = createExecutorService();
        this.stateRepository = new InMemoryStateRepository();
        this.flowRepository = new InMemoryFlowRepository();
        this.metricsCollector = new MetricsCollector(config);
        this.pluginManager = new FlowPluginManager(config.pluginsPath());
        this.stateManager = createStateManager();

        // Inicializa engine de execução
        this.flowEngine = createFlowEngine();

        logger.info("ArchFlow Agent iniciado com configuração: " + config);
    }

    protected StateManager getStateManager() {
        return this.stateManager;
    }

    /**
     * Executa um fluxo de forma assíncrona
     */
    public CompletableFuture<FlowResult> executeFlow(Flow flow, Map<String, Object> input) {
        String flowId = flow.getId();
        logger.info("Iniciando execução do fluxo: " + flowId);

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Registra início da execução
                metricsCollector.recordFlowStart(flowId);

                // Carrega plugins necessários
                pluginManager.loadPluginsForFlow(flow);

                // Salva fluxo no repositório
                flowRepository.save(flow);

                // Inicia execução
                return flowEngine.startFlow(flowId, input)
                        .whenComplete((result, error) -> {
                            if (error != null) {
                                logger.severe("Erro executando fluxo " + flowId + ": " + error.getMessage());
                                metricsCollector.recordFlowError(flowId, error);
                            } else {
                                logger.info("Fluxo " + flowId + " concluído com status: " + result.getStatus());
                                metricsCollector.recordFlowCompletion(flowId, result.getMetrics(),
                                        result.getStatus() == ExecutionStatus.COMPLETED);
                            }
                        })
                        .get(); // Aguarda conclusão

            } catch (Exception e) {
                logger.severe("Erro executando fluxo: " + flowId + " - " + e.getMessage());
                metricsCollector.recordFlowError(flowId, e);
                throw new RuntimeException("Erro executando fluxo: " + flowId, e);
            }
        }, executorService);
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
     * Retoma a execução de um fluxo pausado
     */
    public CompletableFuture<FlowResult> resumeFlow(String flowId) {
        logger.info("Retomando fluxo: " + flowId);
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Carrega estado atual
                FlowState state = getStateManager().loadState(flowId);
                if (state == null) {
                    throw new IllegalStateException("Estado não encontrado para fluxo: " + flowId);
                }

                // Carrega fluxo do repositório
                Flow flow = flowRepository.findById(flowId)
                        .orElseThrow(() -> new IllegalStateException("Fluxo não encontrado: " + flowId));

                // Cria contexto para retomada
                ExecutionContext context = FlowContextBuilder.forFlow(flow)
                        .withInitialVariables(state.getVariables())
                        .withAdditionalContext(Map.of(
                                "resumeTime", System.currentTimeMillis(),
                                "previousState", state.getStatus()
                        ))
                        .build();

                // Retoma execução
                return flowEngine.resumeFlow(flowId, context)
                        .whenComplete((result, error) -> {
                            if (error != null) {
                                logger.severe("Erro retomando fluxo " + flowId + ": " + error.getMessage());
                                metricsCollector.recordFlowError(flowId, error);
                            } else {
                                logger.info("Fluxo " + flowId + " retomado com status: " + result.getStatus());
                                metricsCollector.recordFlowStatus(flowId, FlowStatus.RUNNING);
                            }
                        })
                        .get();

            } catch (Exception e) {
                logger.severe("Erro retomando fluxo: " + flowId + " - " + e.getMessage());
                throw new RuntimeException("Erro retomando fluxo: " + flowId, e);
            }
        }, executorService);
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

            logger.info("ArchFlow Agent finalizado com sucesso");

        } catch (Exception e) {
            logger.severe("Erro finalizando ArchFlow Agent: " + e.getMessage());
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private FlowEngine createFlowEngine() {
        ExecutionManager executionManager = new DefaultExecutionManager(
                createFlowExecutor(),
                createParallelExecutor(),
                executorService
        );

        FlowValidator flowValidator = new DefaultFlowValidator();
        StateManager stateManager = createStateManager();

        return new DefaultFlowEngine(
                executionManager,
                flowRepository,
                stateManager,
                flowValidator
        );
    }

    private ExecutorService createExecutorService() {
        ThreadFactory threadFactory = r -> {
            Thread t = new Thread(r);
            t.setName("archflow-agent-" + t.getId());
            return t;
        };

        return new ThreadPoolExecutor(
                config.resourceConfig().maxThreads(),
                config.resourceConfig().maxThreads(),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                threadFactory,
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
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
        return new DefaultFlowExecutor(
                pluginManager.getPluginClassLoader(),
                metricsCollector
        );
    }

    protected ParallelExecutor createParallelExecutor() {
        return new DefaultParallelExecutor(
                executorService,
                config.maxConcurrentFlows()
        );
    }
}