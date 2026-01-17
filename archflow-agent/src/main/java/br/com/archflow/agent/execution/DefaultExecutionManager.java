package br.com.archflow.agent.execution;

import br.com.archflow.engine.core.ExecutionManager;
import br.com.archflow.engine.execution.FlowExecutor;
import br.com.archflow.engine.execution.ParallelExecutor;
import br.com.archflow.model.engine.ExecutionMetrics;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.flow.*;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.error.ExecutionError;
import br.com.archflow.model.enums.StepStatus;
import br.com.archflow.model.flow.Flow;

import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Implementação do ExecutionManager que gerencia a execução de fluxos.
 */
public class DefaultExecutionManager implements ExecutionManager {
    private static final Logger logger = Logger.getLogger(DefaultExecutionManager.class.getName());

    private final FlowExecutor flowExecutor;
    private final ParallelExecutor parallelExecutor;
    private final ExecutorService executorService;
    private final Map<String, ExecutionControl> activeExecutions;

    public DefaultExecutionManager(
        FlowExecutor flowExecutor,
        ParallelExecutor parallelExecutor,
        ExecutorService executorService
    ) {
        this.flowExecutor = flowExecutor;
        this.parallelExecutor = parallelExecutor;
        this.executorService = executorService;
        this.activeExecutions = new ConcurrentHashMap<>();
    }

    @Override
    public FlowResult executeFlow(Flow flow, ExecutionContext context) {
        String flowId = flow.getId();
        logger.info("Iniciando execução do fluxo: " + flowId);

        try {
            // Registra controle de execução
            ExecutionControl control = new ExecutionControl(flowId);
            activeExecutions.put(flowId, control);

            // Executa o fluxo
            FlowResult result = flowExecutor.execute(flow, context);

            // Remove controle ao finalizar
            activeExecutions.remove(flowId);

            return result;

        } catch (Exception e) {
            logger.severe("Erro executando fluxo " + flowId + ": " + e.getMessage());
            activeExecutions.remove(flowId);
            
            // Cria erro de execução
            ExecutionError error = ExecutionError.fromException(
                "FLOW_EXECUTION_ERROR",
                e,
                "ExecutionManager"
            );

            // Retorna resultado com erro
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
                    return context.getMetrics();
                }

                @Override
                public List<ExecutionError> getErrors() {
                    return List.of(error);
                }
            };
        }
    }

    @Override
    public void pauseFlow(String flowId) {
        logger.info("Pausando fluxo: " + flowId);
        ExecutionControl control = activeExecutions.get(flowId);
        if (control != null) {
            control.pause();
        }
    }

    @Override
    public void stopFlow(String flowId) {
        logger.info("Parando fluxo: " + flowId);
        ExecutionControl control = activeExecutions.get(flowId);
        if (control != null) {
            control.stop();
            activeExecutions.remove(flowId);
        }
    }

    /**
     * Executa uma lista de passos em paralelo
     */
    public List<StepResult> executeParallelSteps(List<FlowStep> steps, ExecutionContext context) {
        return parallelExecutor.executeParallel(steps, context);
    }

    /**
     * Classe interna para controle de execução
     */
    private static class ExecutionControl {
        private final String flowId;
        private volatile boolean paused;
        private volatile boolean stopped;
        private final Set<String> completedSteps;
        private final Set<String> failedSteps;

        public ExecutionControl(String flowId) {
            this.flowId = flowId;
            this.paused = false;
            this.stopped = false;
            this.completedSteps = ConcurrentHashMap.newKeySet();
            this.failedSteps = ConcurrentHashMap.newKeySet();
        }

        public void pause() {
            this.paused = true;
        }

        public void resume() {
            this.paused = false;
        }

        public void stop() {
            this.stopped = true;
        }

        public boolean isPaused() {
            return paused;
        }

        public boolean isStopped() {
            return stopped;
        }

        public void markStepCompleted(String stepId) {
            completedSteps.add(stepId);
        }

        public void markStepFailed(String stepId) {
            failedSteps.add(stepId);
        }

        public Set<String> getCompletedSteps() {
            return Collections.unmodifiableSet(completedSteps);
        }

        public Set<String> getFailedSteps() {
            return Collections.unmodifiableSet(failedSteps);
        }
    }
}