package br.com.archflow.standalone.model;

import br.com.archflow.model.config.FlowConfiguration;
import br.com.archflow.model.config.LLMConfig;
import br.com.archflow.model.config.RetryPolicy;

import java.util.Map;
import java.util.Set;

/**
 * Serializable implementation of FlowConfiguration.
 */
public class SerializableFlowConfig implements FlowConfiguration {

    private long timeout;
    private RetryPolicy retryPolicy;
    private LLMConfig llmConfig;
    private SerializableMonitoringConfig monitoringConfig;

    public SerializableFlowConfig() {} // Jackson

    public SerializableFlowConfig(long timeout, RetryPolicy retryPolicy, LLMConfig llmConfig,
                                   SerializableMonitoringConfig monitoringConfig) {
        this.timeout = timeout;
        this.retryPolicy = retryPolicy;
        this.llmConfig = llmConfig;
        this.monitoringConfig = monitoringConfig;
    }

    public static SerializableFlowConfig from(FlowConfiguration config) {
        // RetryPolicy has Set<Class<? extends Throwable>> which isn't easily serializable
        RetryPolicy rp = config.getRetryPolicy();
        RetryPolicy serializableRp = rp != null
                ? new RetryPolicy(rp.maxAttempts(), rp.delay(), rp.multiplier(), Set.of())
                : null;

        return new SerializableFlowConfig(
                config.getTimeout(),
                serializableRp,
                config.getLLMConfig(),
                null // MonitoringConfig mapped separately if needed
        );
    }

    @Override public long getTimeout() { return timeout; }
    @Override public RetryPolicy getRetryPolicy() { return retryPolicy; }
    @Override public LLMConfig getLLMConfig() { return llmConfig; }
    @Override public br.com.archflow.model.config.MonitoringConfig getMonitoringConfig() { return monitoringConfig; }

    public void setTimeout(long timeout) { this.timeout = timeout; }
    public void setRetryPolicy(RetryPolicy retryPolicy) { this.retryPolicy = retryPolicy; }
    public void setLlmConfig(LLMConfig llmConfig) { this.llmConfig = llmConfig; }
    public void setMonitoringConfig(SerializableMonitoringConfig monitoringConfig) { this.monitoringConfig = monitoringConfig; }

    /**
     * Serializable MonitoringConfig since the model version uses LogLevel enum.
     */
    public static class SerializableMonitoringConfig extends br.com.archflow.model.config.MonitoringConfig {
        public SerializableMonitoringConfig() {
            super(false, false, br.com.archflow.model.enums.LogLevel.INFO, Map.of());
        }
        public SerializableMonitoringConfig(boolean detailedMetrics, boolean fullHistory,
                                             br.com.archflow.model.enums.LogLevel logLevel, Map<String, String> tags) {
            super(detailedMetrics, fullHistory, logLevel, tags);
        }
    }
}
