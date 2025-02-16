package br.com.archflow.model.flow;

/**
 * Tipos possíveis de passos em um fluxo.
 */
public enum StepType {
    /** Representa uma Chain do LangChain4j */
    CHAIN,
    
    /** Representa um Agent do LangChain4j */
    AGENT,
    
    /** Representa uma Tool do LangChain4j */
    TOOL,
    
    /** Representa um passo customizado */
    CUSTOM
}