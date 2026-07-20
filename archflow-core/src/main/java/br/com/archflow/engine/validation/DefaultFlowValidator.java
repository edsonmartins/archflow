package br.com.archflow.engine.validation;

import br.com.archflow.engine.exceptions.FlowValidationException;
import br.com.archflow.engine.exceptions.ValidationError;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Implementação padrão do validador de fluxos.
 */
public class DefaultFlowValidator implements FlowValidator {

    private final br.com.archflow.engine.execution.ConditionEvaluator conditionEvaluator =
            new br.com.archflow.engine.execution.ConditionEvaluator();

    @Override
    public void validate(Flow flow) throws FlowValidationException {
        List<ValidationError> errors = new ArrayList<>();
        ValidationContext context = new ValidationContext(flow);

        // Valida identificação básica
        validateBasicInfo(flow, errors);

        // Valida passos
        for (FlowStep step : flow.getSteps()) {
            try {
                validateStep(step, context);
            } catch (FlowValidationException e) {
                errors.addAll(e.getErrors());
            }
        }

        // Valida conexões
        validateConnections(flow, errors);

        // Valida ciclos
        validateCycles(flow, errors);

        if (!errors.isEmpty()) {
            throw new FlowValidationException(errors);
        }
    }

    @Override
    public void validateStep(FlowStep step, ValidationContext context) throws FlowValidationException {
        List<ValidationError> errors = new ArrayList<>();

        // Valida identificação do passo
        if (step.getId() == null || step.getId().trim().isEmpty()) {
            errors.add(new ValidationError(
                "step.id",
                "Step ID is required",
                "STEP_ID_REQUIRED",
                Map.of("step", step)
            ));
        }

        // Valida tipo do passo
        if (step.getType() == null) {
            errors.add(new ValidationError(
                "step.type",
                "Step type is required",
                "STEP_TYPE_REQUIRED",
                Map.of("step", step)
            ));
        }

        // Valida configuração do passo
        if (step.getType() != null) {
            validateStepConfiguration(step, errors);
        }

        // Valida conexões do passo
        validateStepConnections(step, context, errors);

        if (!errors.isEmpty()) {
            throw new FlowValidationException(errors);
        }
    }

    private void validateBasicInfo(Flow flow, List<ValidationError> errors) {
        if (flow.getId() == null || flow.getId().trim().isEmpty()) {
            errors.add(new ValidationError(
                "flow.id",
                "Flow ID is required",
                "FLOW_ID_REQUIRED",
                Map.of()
            ));
        }

        if (flow.getSteps().isEmpty()) {
            errors.add(new ValidationError(
                "flow.steps",
                "Flow must have at least one step",
                "FLOW_EMPTY",
                Map.of()
            ));
        }
    }

    private void validateConnections(Flow flow, List<ValidationError> errors) {
        Set<String> stepIds = flow.getSteps().stream()
            .map(FlowStep::getId)
            .collect(Collectors.toSet());

        for (FlowStep step : flow.getSteps()) {
            for (StepConnection connection : step.getConnections()) {
                // Valida existência dos passos conectados
                if (!stepIds.contains(connection.getSourceId())) {
                    errors.add(new ValidationError(
                        "connection.source",
                        "Source step does not exist: " + connection.getSourceId(),
                        "INVALID_CONNECTION_SOURCE",
                        Map.of("connection", connection)
                    ));
                }

                if (!stepIds.contains(connection.getTargetId())) {
                    errors.add(new ValidationError(
                        "connection.target",
                        "Target step does not exist: " + connection.getTargetId(),
                        "INVALID_CONNECTION_TARGET",
                        Map.of("connection", connection)
                    ));
                }

                // Valida condições
                connection.getCondition().ifPresent(condition -> 
                    validateCondition(condition, errors, connection)
                );
            }
        }
    }

    private void validateCycles(Flow flow, List<ValidationError> errors) {
        // Implementa detecção de ciclos usando DFS
        Set<String> visited = new HashSet<>();
        Set<String> currentPath = new HashSet<>();

        for (FlowStep step : flow.getSteps()) {
            if (hasCycle(step, visited, currentPath, flow)) {
                errors.add(new ValidationError(
                    "flow.cycle",
                    "Flow contains cycles",
                    "FLOW_CYCLE_DETECTED",
                    Map.of("startStep", step.getId())
                ));
                break;
            }
        }
    }

    private boolean hasCycle(FlowStep step, Set<String> visited, Set<String> currentPath, Flow flow) {
        String stepId = step.getId();
        
        if (currentPath.contains(stepId)) {
            return true;
        }
        
        if (visited.contains(stepId)) {
            return false;
        }

        visited.add(stepId);
        currentPath.add(stepId);

        for (StepConnection connection : step.getConnections()) {
            String targetId = connection.getTargetId();
            Optional<FlowStep> targetStep = flow.getSteps().stream()
                .filter(s -> s.getId().equals(targetId))
                .findFirst();

            if (targetStep.isPresent() && hasCycle(targetStep.get(), visited, currentPath, flow)) {
                return true;
            }
        }

        currentPath.remove(stepId);
        return false;
    }

    private void validateStepConfiguration(FlowStep step, List<ValidationError> errors) {
        // Validações específicas para cada tipo de passo
        switch (step.getType()) {
            case CHAIN:
                validateChainConfiguration(step, errors);
                break;
            case AGENT:
                validateAgentConfiguration(step, errors);
                break;
            case TOOL:
                validateToolConfiguration(step, errors);
                break;
            case ASSISTANT:
            case CUSTOM:
            case ORCHESTRATE:
                // Executable steps with no structural config to validate up front
                // (they validate their inputs at runtime). Notably ORCHESTRATE
                // (ADR-0002/design-0004) decides its sub-graph dynamically.
                break;
            default:
                errors.add(new ValidationError(
                    "step.type",
                    "Unsupported step type: " + step.getType(),
                    "UNSUPPORTED_STEP_TYPE",
                    Map.of("step", step)
                ));
        }
    }

    // CHAIN/AGENT/TOOL: a interface FlowStep não expõe a configuração do
    // componente — ela é validada na materialização (DefaultFlowStepFactory
    // no archflow-api chama ComponentPlugin.validateConfig) e em runtime.
    // Aqui só existe estrutura de grafo para validar; estes hooks ficam
    // como pontos de extensão deliberadamente vazios.

    private void validateChainConfiguration(FlowStep step, List<ValidationError> errors) {
        // Sem estrutura visível no nível do modelo; ver comentário acima.
    }

    private void validateAgentConfiguration(FlowStep step, List<ValidationError> errors) {
        // Sem estrutura visível no nível do modelo; ver comentário acima.
    }

    private void validateToolConfiguration(FlowStep step, List<ValidationError> errors) {
        // Sem estrutura visível no nível do modelo; ver comentário acima.
    }

    private void validateStepConnections(FlowStep step, ValidationContext context, List<ValidationError> errors) {
        for (StepConnection connection : step.getConnections()) {
            if (connection.getTargetId() == null || connection.getTargetId().isBlank()) {
                errors.add(new ValidationError(
                    "connection.target",
                    "Connection of step '" + step.getId() + "' has no target",
                    "CONNECTION_TARGET_REQUIRED",
                    Map.of("step", step.getId())
                ));
            }
            // As conexões ficam no step de ORIGEM; uma conexão gravada aqui com
            // sourceId de outro step é ignorada pelo executor — sinaliza o erro
            // na validação em vez de falhar silenciosamente em runtime.
            String sourceId = connection.getSourceId();
            if (sourceId != null && !sourceId.isBlank() && !sourceId.equals(step.getId())) {
                errors.add(new ValidationError(
                    "connection.source",
                    "Connection stored on step '" + step.getId()
                        + "' declares a different source: '" + sourceId + "'",
                    "CONNECTION_SOURCE_MISMATCH",
                    Map.of("step", step.getId(), "sourceId", sourceId)
                ));
            }
        }
    }

    private void validateCondition(String condition, List<ValidationError> errors, StepConnection connection) {
        if (!conditionEvaluator.isWellFormed(condition)) {
            errors.add(new ValidationError(
                "connection.condition",
                "Malformed transition condition: \"" + condition + "\"",
                "CONNECTION_CONDITION_MALFORMED",
                Map.of("condition", condition,
                       "connection", connection.getSourceId() + "->" + connection.getTargetId())
            ));
        }
    }
}
