package br.com.archflow.dsl;

import java.util.List;

/**
 * O fluxo escrito na DSL não é coerente.
 *
 * <p>Carrega <b>todos</b> os problemas encontrados, não só o primeiro: quem
 * escreveu um fluxo de vinte passos com três arestas erradas não deveria
 * precisar de três compilações para saber disso.
 *
 * @since 1.1.0
 */
public class DslValidationException extends RuntimeException {

    private final List<String> problems;

    DslValidationException(String message) {
        super(message);
        this.problems = List.of(message);
    }

    DslValidationException(String workflowId, List<String> problems) {
        super(format(workflowId, problems));
        this.problems = List.copyOf(problems);
    }

    /** Os problemas, um por item, na ordem em que foram detectados. */
    public List<String> getProblems() {
        return problems;
    }

    private static String format(String workflowId, List<String> problems) {
        StringBuilder message = new StringBuilder("fluxo '")
                .append(workflowId)
                .append("' inválido (")
                .append(problems.size())
                .append(problems.size() == 1 ? " problema)" : " problemas)");
        for (String problem : problems) {
            message.append("\n  - ").append(problem);
        }
        return message.toString();
    }
}
