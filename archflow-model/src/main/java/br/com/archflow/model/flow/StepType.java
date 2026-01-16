package br.com.archflow.model.flow;

/**
 * Tipos possíveis de passos em um fluxo.
 */
public enum StepType {
    ASSISTANT,

    AGENT,

    TOOL,

    CHAIN,

    CUSTOM
}