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
     *
     * <p>Em português e no singular certo, como os motivos que vêm depois (RCHFLW-133): a tela mostrava
     * "Flow validation failed: 1 errors found: «…» não é um valor". Quem lê o erro por programa usa
     * {@link #getErrors()} — o {@code code} de cada um —, não esta frase; o invoke do VendaX só a repassa
     * como texto.</p>
     */
    private static String message(List<ValidationError> errors) {
        int n = errors.size();
        String base = "O fluxo não passou na validação: " + n + (n == 1 ? " erro encontrado" : " erros encontrados");
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