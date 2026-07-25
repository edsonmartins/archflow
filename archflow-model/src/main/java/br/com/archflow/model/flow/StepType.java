package br.com.archflow.model.flow;

/**
 * Tipos possíveis de passos em um fluxo.
 */
public enum StepType {
    ASSISTANT,

    AGENT,

    TOOL,

    CHAIN,

    CUSTOM,

    /**
     * Dynamic multi-agent orchestration node (ADR-0002 / design-0004): decompose
     * a goal, fan out to catalog-routed agents, adversarially verify and loop
     * until convergence, bounded by a token budget.
     */
    ORCHESTRATE,

    /**
     * Human-in-the-loop gate: suspends the flow in
     * {@link FlowStatus#AWAITING_APPROVAL} with a proposal for a person to
     * review, and only continues once a decision is submitted. The suspension
     * is durable — the engine persists the state, so the flow survives a
     * process restart while it waits.
     */
    APPROVAL
}