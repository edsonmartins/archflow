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
    ORCHESTRATE
}