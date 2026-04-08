package br.com.archflow.standalone.model;

import br.com.archflow.model.config.FlowConfiguration;
import br.com.archflow.model.config.LLMConfig;
import br.com.archflow.model.config.MonitoringConfig;
import br.com.archflow.model.config.RetryPolicy;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Set;

/**
 * Serializable implementation of FlowConfiguration.
 */
public class SerializableFlowConfig implements FlowConfiguration {

    private long timeout;
    private RetryPolicy retryPolicy;
    @JsonProperty("llmConfig")
    private LLMConfig llmConfig;
    private MonitoringConfig monitoringConfig;

    public SerializableFlowConfig() {} // Jackson

    public SerializableFlowConfig(long timeout, RetryPolicy retryPolicy, LLMConfig llmConfig,
                                   MonitoringConfig monitoringConfig) {
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
                config.getMonitoringConfig()
        );
    }

    @Override public long getTimeout() { return timeout; }
    @Override public RetryPolicy getRetryPolicy() { return retryPolicy; }
    @Override @JsonProperty("llmConfig") public LLMConfig getLLMConfig() { return llmConfig; }
    @Override public MonitoringConfig getMonitoringConfig() { return monitoringConfig; }

    public void setTimeout(long timeout) { this.timeout = timeout; }
    public void setRetryPolicy(RetryPolicy retryPolicy) { this.retryPolicy = retryPolicy; }
    @JsonProperty("llmConfig")
    public void setLlmConfig(LLMConfig llmConfig) { this.llmConfig = llmConfig; }
    public void setMonitoringConfig(MonitoringConfig monitoringConfig) { this.monitoringConfig = monitoringConfig; }
}
