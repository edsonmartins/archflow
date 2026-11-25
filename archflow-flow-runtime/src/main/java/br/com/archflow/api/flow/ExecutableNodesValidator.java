package br.com.archflow.api.flow;

import br.com.archflow.engine.exceptions.FlowValidationException;
import br.com.archflow.engine.exceptions.ValidationError;
import br.com.archflow.engine.validation.FlowValidator;
import br.com.archflow.engine.validation.ValidationContext;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowStep;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A validação que o motor faz antes do primeiro passo, mais uma regra: nenhum nó de tipo sem execução
 * ({@link UnsupportedNodeStep}, RCHFLW-11).
 *
 * <p>Sem ela, o fluxo rodava os passos anteriores — com efeito, chamadas pagas ao modelo — e só
 * falhava ao chegar no nó, com {@code component not found}. Aqui a execução termina antes de começar,
 * com uma mensagem por tipo que nomeia os nós: o usuário sabe o que tirar do fluxo.</p>
 *
 * <p>Embrulha o validador do motor em vez de mudá-lo: o archflow-core não conhece os passos do
 * archflow-api, e a regra é deste runtime. Os erros dos dois se somam numa só falha.</p>
 *
 * <p>Fora do servidor (runner standalone, SDK embarcado) vale a mesma regra para o nó que precisa do
 * servidor ({@link ServerOnlyNodeStep}, RCHFLW-37): um erro por nó, com o motivo. No servidor esse passo
 * não existe — a fábrica recebe os {@link ServerNodes} — e nada muda.</p>
 */
public final class ExecutableNodesValidator implements FlowValidator {

    static final String CODE = "NODE_TYPE_NOT_EXECUTABLE";
    static final String SERVER_CODE = "NODE_REQUIRES_SERVER";

    private final FlowValidator delegate;

    public ExecutableNodesValidator(FlowValidator delegate) {
        this.delegate = delegate;
    }

    @Override
    public void validate(Flow flow) throws FlowValidationException {
        List<ValidationError> errors = new ArrayList<>();
        try {
            delegate.validate(flow);
        } catch (FlowValidationException e) {
            errors.addAll(e.getErrors());
        }
        errors.addAll(unsupported(flow));
        errors.addAll(serverOnly(flow));
        if (!errors.isEmpty()) {
            throw new FlowValidationException(errors);
        }
    }

    @Override
    public void validateStep(FlowStep step, ValidationContext context) throws FlowValidationException {
        delegate.validateStep(step, context);
    }

    /** Um erro por tipo, na ordem em que o tipo aparece, com os nós que o usam. */
    private static List<ValidationError> unsupported(Flow flow) {
        Map<String, List<String>> nodesByKind = new LinkedHashMap<>();
        for (FlowStep step : flow.getSteps()) {
            if (step instanceof UnsupportedNodeStep unsupported) {
                nodesByKind.computeIfAbsent(unsupported.kind(), k -> new ArrayList<>()).add(unsupported.describe());
            }
        }
        List<ValidationError> errors = new ArrayList<>();
        nodesByKind.forEach((kind, nodes) -> errors.add(new ValidationError(
                "step.type",
                NodesWithoutExecution.message(kind) + " — nó(s): " + String.join(", ", nodes),
                CODE,
                Map.of("type", kind, "nodes", List.copyOf(nodes)))));
        return errors;
    }

    /** Um erro por nó que precisa do servidor, na ordem dos passos. */
    private static List<ValidationError> serverOnly(Flow flow) {
        List<ValidationError> errors = new ArrayList<>();
        for (FlowStep step : flow.getSteps()) {
            if (step instanceof ServerOnlyNodeStep node) {
                errors.add(new ValidationError("step.type", node.message(), SERVER_CODE,
                        Map.of("node", node.getId(), "capability", node.capability().name())));
            }
        }
        return errors;
    }
}
