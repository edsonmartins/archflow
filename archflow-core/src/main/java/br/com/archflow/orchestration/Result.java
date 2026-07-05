package br.com.archflow.orchestration;

/**
 * Outcome of a single {@link Worker} invocation in a fan-out. Carries {@link Usage}
 * so the orchestrator can charge the {@link BudgetLedger}.
 */
public record Result<O>(O value, boolean ok, String error, Usage usage) {

    public static <O> Result<O> success(O value, Usage usage) {
        return new Result<>(value, true, null, usage == null ? Usage.ZERO : usage);
    }

    public static <O> Result<O> success(O value) {
        return success(value, Usage.ZERO);
    }

    public static <O> Result<O> fail(String error) {
        return new Result<>(null, false, error, Usage.ZERO);
    }
}
