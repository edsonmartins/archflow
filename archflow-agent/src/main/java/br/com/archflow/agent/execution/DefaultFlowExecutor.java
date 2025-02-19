package br.com.archflow.agent.execution;

import br.com.archflow.engine.execution.FlowExecutor;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Executor de fluxos padrão
 */
public class DefaultFlowExecutor implements FlowExecutor {
    private static final Logger logger = Logger.getLogger(DefaultFlowExecutor.class.getName());

    private final ClassLoader pluginClassLoader;
    private final MetricsCollector metricsCollector;
    private final Map<String, StepExecution> activeExecutions;
    private volatile Flow currentFlow;

    public DefaultFlowExecutor(ClassLoader pluginClassLoader, MetricsCollector metricsCollector) {
        this.pluginClassLoader = pluginClassLoader;
        this.metricsCollector = metricsCollector;
        this.activeExecutions = new ConcurrentHashMap<>();
    }

    @Override
    public FlowResult execute(Flow flow, ExecutionContext context) {
        String flowId = flow.getId();
        Thread.currentThread().setContextClassLoader(pluginClassLoader);
        this.currentFlow = flow;

        try {
            logger.info("Iniciando execução do fluxo: " + flowId);
            metricsCollector.recordFlowStart(flowId);

            // Executa passos do fluxo
            List<StepResult> results = executeSteps(flow.getSteps(), context, flow);

            // Verifica resultado final
            boolean success = results.stream()
                    .allMatch(r -> r.getStatus() == StepStatus.COMPLETED);

            // Retorna resultado
            return new FlowResult() {
                @Override
                public ExecutionStatus getStatus() {
                    return success ? ExecutionStatus.COMPLETED : ExecutionStatus.FAILED;
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
        } finally {
            this.currentFlow = null;
        }
    }

    @Override
    public void handleResult(StepResult result) {
        String stepId = result.getStepId();
        logger.info("Processando resultado do step: " + stepId);

        StepExecution execution = activeExecutions.get(stepId);
        if (execution == null) {
            logger.warning("Step execution não encontrada para: " + stepId);
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
            activeExecutions.remove(stepId);

        } catch (Exception e) {
            logger.severe("Erro processando resultado do step " + stepId + ": " + e.getMessage());
            execution.fail(e);
        }
    }

    private void handleSuccess(StepExecution execution, StepResult result) {
        logger.info("Step " + result.getStepId() + " concluído com sucesso");

        // Atualiza estado do fluxo
        execution.getContext().set("step." + result.getStepId() + ".output",
                result.getOutput().orElse(null));

        // Executa próximos passos se houver
        List<FlowStep> nextSteps = findNextSteps(execution.getFlow(), result.getStepId());
        if (!nextSteps.isEmpty()) {
            executeSteps(nextSteps, execution.getContext(), execution.getFlow());
        }
    }

    private void handleFailure(StepExecution execution, StepResult result) {
        logger.severe("Step " + result.getStepId() + " falhou: " +
                result.getErrors().stream()
                        .map(StepError::message)
                        .findFirst().orElse("Sem mensagem de erro"));

        // Registra erro no contexto
        execution.getContext().set("step." + result.getStepId() + ".error",
                result.getErrors());

        // Executa caminhos de erro se definidos
        List<FlowStep> errorSteps = findErrorSteps(execution.getFlow(), result.getStepId());
        if (!errorSteps.isEmpty()) {
            executeSteps(errorSteps, execution.getContext(), execution.getFlow());
        }
    }

    private void handleSkipped(StepExecution execution, StepResult result) {
        logger.info("Step " + result.getStepId() + " ignorado");

        // Executa próximos passos normalmente
        List<FlowStep> nextSteps = findNextSteps(execution.getFlow(), result.getStepId());
        if (!nextSteps.isEmpty()) {
            executeSteps(nextSteps, execution.getContext(), execution.getFlow());
        }
    }

    private List<FlowStep> findNextSteps(Flow flow, String stepId) {
        return flow.getSteps().stream()
                .filter(step -> step.getConnections().stream()
                        .anyMatch(conn -> !conn.isErrorPath() &&
                                conn.getSourceId().equals(stepId)))
                .collect(Collectors.toList());
    }

    private List<FlowStep> findErrorSteps(Flow flow, String stepId) {
        return flow.getSteps().stream()
                .filter(step -> step.getConnections().stream()
                        .anyMatch(conn -> conn.isErrorPath() &&
                                conn.getSourceId().equals(stepId)))
                .collect(Collectors.toList());
    }

    private List<StepResult> executeSteps(List<FlowStep> steps, ExecutionContext context, Flow flow) {
        return steps.stream()
                .map(step -> executeStep(step, context, flow))
                .toList();
    }

    private StepResult executeStep(FlowStep step, ExecutionContext context, Flow flow) {
        String stepId = step.getId();
        logger.info("Executando step: " + stepId);

        try {
            // Registra execução ativa
            StepExecution execution = new StepExecution(flow, step, context);
            activeExecutions.put(stepId, execution);

            return step.execute(context)
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            logger.severe("Erro executando step " + stepId + ": " + error.getMessage());
                            handleResult(createErrorResult(step, error));
                        } else {
                            handleResult(result);
                        }
                    })
                    .get();

        } catch (Exception e) {
            logger.severe("Erro executando step: " + stepId + " - " + e.getMessage());
            return createErrorResult(step, e);
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

        public StepExecution(Flow flow, FlowStep step, ExecutionContext context) {
            this.flow = flow;
            this.step = step;
            this.context = context;
            this.flowId = context.getState().getFlowId();
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