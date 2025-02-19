package br.com.archflow.engine.exceptions;

import java.util.Collections;
import java.util.List;

/**
 * Exceção lançada durante validação de fluxos.
 */
public class FlowValidationException extends FlowException {
    private final List<ValidationError> errors;

    public FlowValidationException(List<ValidationError> errors) {
        super("Flow validation failed: " + errors.size() + " errors found");
        this.errors = errors;
    }

    public List<ValidationError> getErrors() {
        return Collections.unmodifiableList(errors);
    }
}