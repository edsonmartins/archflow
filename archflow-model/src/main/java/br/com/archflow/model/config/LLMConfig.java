package br.com.archflow.model.config;

import java.util.Map;

/**
 * Configurações específicas do LLM declaradas no nível do fluxo.
 *
 * <p>Para a configuração efetiva (após herança step/agente/flow/tenant/plataforma)
 * veja {@link ResolvedLLMConfig}; para overrides parciais, {@link LLMConfigPatch}.
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
) {
    /**
     * Converte esta config de fluxo em um {@link LLMConfigPatch} — todos os
     * campos presentes. Usado como nível "flow" na cadeia de resolução.
     */
    public LLMConfigPatch toPatch() {
        return LLMConfigPatch.builder()
            .model(model)
            .temperature(temperature)
            .maxTokens(maxTokens)
            .timeout(timeout)
            .additionalConfig(additionalConfig)
            .build();
    }
}