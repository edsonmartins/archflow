package br.com.archflow.model.config;

import java.util.Map;

/**
 * Configurações específicas do LLM.
 */
public record LLMConfig(
    /** Modelo a ser usado */
    String model,
    
    /** Temperatura para geração */
    double temperature,
    
    /** Máximo de tokens na resposta */
    int maxTokens,
    
    /** Timeout específico para chamadas LLM */
    long timeout,
    
    /** Configurações adicionais */
    Map<String, Object> additionalConfig
) {}