package br.com.archflow.model.engine;

/**
 * Chaves bem-conhecidas do {@link ExecutionContext} para passar config resolvida
 * de LLM para os adapters em runtime.
 *
 * <p>Contrato entre quem RESOLVE (runner/produto, via {@code LLMConfigResolver})
 * e quem CONSOME (adapters de chat). O engine permanece provider-agnostic: ele não
 * resolve modelo — só transporta o resultado por estas chaves.
 *
 * @since 1.0.0
 */
public final class ExecutionKeys {

    private ExecutionKeys() {
    }

    /**
     * Config de LLM já resolvida para o passo atual — valor do tipo
     * {@code br.com.archflow.model.config.ResolvedLLMConfig}. Quando presente,
     * adapters devem usá-la (model/temperature/maxTokens/…) em vez do default
     * estático configurado.
     */
    public static final String LLM_RESOLVED_CONFIG = "archflow.llm.resolved";

    /**
     * Override legado apenas do nome do modelo (String). Mantido para
     * compatibilidade; prefira {@link #LLM_RESOLVED_CONFIG}.
     */
    public static final String LLM_MODEL = "llm.model";

    // ── Human-in-the-loop ───────────────────────────────────────────
    //
    // Contrato entre o engine (que suspende o fluxo em AWAITING_APPROVAL e
    // grava estas chaves nas variáveis persistidas) e a camada de API (que lê
    // o estado durável para montar a fila de aprovações). Ficam aqui, em
    // archflow-model, porque os dois lados precisam do MESMO nome — enquanto
    // eram privadas do engine, a API não tinha como enxergar a fila.

    /** {@code String} — id da solicitação de aprovação pendente. Vazio = consumida. */
    public static final String APPROVAL_REQUEST_ID = "__archflow.approvalRequestId";

    /** Payload que o humano precisa avaliar, como fornecido a {@code requestApproval}. */
    public static final String APPROVAL_PROPOSAL = "__archflow.approvalProposal";

    /** {@code String} — id do step que pediu a aprovação. */
    public static final String APPROVAL_STEP_ID = "__archflow.approvalStepId";

    /** {@code String} ISO-8601 — quando a solicitação foi criada. */
    public static final String APPROVAL_REQUESTED_AT = "__archflow.approvalRequestedAt";

    /** {@code String} — descrição legível exibida ao decisor. */
    public static final String APPROVAL_DESCRIPTION = "__archflow.approvalDescription";

    /** {@code String} {@code APPROVED}|{@code REJECTED} — decisão humana registrada. */
    public static final String APPROVAL_DECISION = "__archflow.approvalDecision";

    /** Payload editado pelo humano ao aprovar, quando houver. */
    public static final String APPROVAL_PAYLOAD = "approvalPayload";
}
