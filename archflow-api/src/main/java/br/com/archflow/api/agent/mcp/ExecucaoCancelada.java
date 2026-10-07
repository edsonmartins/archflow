package br.com.archflow.api.agent.mcp;

/** O laço parou porque quem o acionou pediu. Não é falha do modelo nem da tool. */
public class ExecucaoCancelada extends RuntimeException {

    public ExecucaoCancelada() {
        super("execução cancelada pelo chamador");
    }
}
