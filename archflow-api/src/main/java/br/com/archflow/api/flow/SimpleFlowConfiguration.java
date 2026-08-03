package br.com.archflow.api.flow;

import br.com.archflow.model.config.FlowConfiguration;
import br.com.archflow.model.config.LLMConfig;
import br.com.archflow.model.config.LLMConfigPatch;
import br.com.archflow.model.config.MonitoringConfig;
import br.com.archflow.model.config.RetryPolicy;

/**
 * Configuração mínima do fluxo materializado a partir do documento do
 * designer. O runtime linear ainda não aplica timeout/retry/monitoramento, mas
 * precisa preservar o patch de LLM para a cadeia
 * step &gt; flow &gt; tenant &gt; platform.
 */
final class SimpleFlowConfiguration implements FlowConfiguration {

    private final LLMConfigPatch llmPatch;

    SimpleFlowConfiguration(LLMConfigPatch llmPatch) {
        this.llmPatch = llmPatch == null ? LLMConfigPatch.empty() : llmPatch;
    }

    @Override public long getTimeout() { return 0; }
    @Override public RetryPolicy getRetryPolicy() { return null; }
    @Override public LLMConfig getLLMConfig() { return null; }
    @Override public MonitoringConfig getMonitoringConfig() { return null; }
    @Override public LLMConfigPatch getLLMPatch() { return llmPatch; }
}
