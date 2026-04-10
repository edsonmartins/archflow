package br.com.archflow.conversation.approval;

/**
 * Tipos de decisão humana no protocolo Human-in-the-Loop.
 */
public enum Decision {
    /** A proposta foi aprovada sem alterações. */
    APPROVED,
    /** A proposta foi rejeitada. */
    REJECTED,
    /** A proposta foi editada pelo humano antes de ser aprovada. */
    EDITED
}
