package br.com.archflow.engine.exceptions;

import java.util.Collections;
import java.util.List;

/**
 * Exceção lançada durante validação de fluxos.
 */
public class FlowValidationException extends FlowException {
    private final List<ValidationError> errors;

    public FlowValidationException(List<ValidationError> errors) {
        super(message(errors));
        this.errors = errors;
    }

    /**
     * A contagem e o que cada erro diz. Só a contagem chegava a quem executou: a execução terminava
     * FAILED com "1 errors found" e o motivo ficava no objeto, que nenhuma tela lê (RCHFLW-11).
     */
    private static String message(List<ValidationError> errors) {
        String base = "Flow validation failed: " + errors.size() + " errors found";
        List<String> motivos = errors.stream()
                .map(ValidationError::message)
                .filter(m -> m != null && !m.isBlank())
                .toList();
        return motivos.isEmpty() ? base : base + ": " + String.join("; ", motivos);
    }

    public List<ValidationError> getErrors() {
        return Collections.unmodifiableList(errors);
    }
}