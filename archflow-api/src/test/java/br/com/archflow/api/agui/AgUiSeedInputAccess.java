package br.com.archflow.api.agui;

import br.com.archflow.model.engine.ExecutionContext;

/** Acesso de teste ao {@code AgUiController.seedInput} (package-private) a partir de outros pacotes. */
public final class AgUiSeedInputAccess {

    private AgUiSeedInputAccess() {
    }

    public static void seed(ExecutionContext ctx, RunAgentInput input) {
        AgUiController.seedInput(ctx, input);
    }
}
