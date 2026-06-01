package br.com.archflow.model.config;

/**
 * Configuração específica de um fluxo.
 * Define parâmetros que afetam a execução do fluxo.
 *
 * @since 1.0.0
 */
public interface FlowConfiguration {
    /**
     * Retorna o timeout máximo para execução do fluxo.
     *
     * @return timeout em milissegundos
     */
    long getTimeout();

    /**
     * Retorna a política de retry para erros.
     *
     * @return configuração de retry
     */
    RetryPolicy getRetryPolicy();

    /**
     * Retorna configurações específicas do LLM.
     *
     * @return configurações do modelo
     */
    LLMConfig getLLMConfig();

    /**
     * Retorna o patch de LLM no nível do fluxo para a cadeia de resolução.
     * Por padrão deriva de {@link #getLLMConfig()} (ou vazio se ausente).
     *
     * @return patch de LLM do fluxo
     */
    default LLMConfigPatch getLLMPatch() {
        LLMConfig cfg = getLLMConfig();
        return cfg != null ? cfg.toPatch() : LLMConfigPatch.empty();
    }

    /**
     * Retorna configurações de monitoramento.
     *
     * @return configurações de monitoramento
     */
    MonitoringConfig getMonitoringConfig();
}