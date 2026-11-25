package br.com.archflow.standalone.model;

import br.com.archflow.model.config.FlowConfiguration;
import br.com.archflow.model.config.LLMConfig;
import br.com.archflow.model.config.LLMConfigPatch;
import br.com.archflow.model.config.MonitoringConfig;
import br.com.archflow.model.config.RetryPolicy;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
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

    /**
     * Escopo de componentes do fluxo ({@code configuration.allowedComponents}, lido pelo
     * {@code DefaultWorkflowDeserializer}). Sem este campo o POJO descartava a lista: abrir a aba
     * Código e Salvar YAML deixava o fluxo restrito rodando irrestrito (RCHFLW-63). Nulo = ausente
     * (o documento não declarou) e some da saída; lista vazia é outra coisa — nega tudo — e por isso
     * é mantida como veio.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<String> allowedComponents;

    public SerializableFlowConfig() {} // Jackson

    public SerializableFlowConfig(long timeout, RetryPolicy retryPolicy, LLMConfig llmConfig,
                                   MonitoringConfig monitoringConfig) {
        this(timeout, retryPolicy, llmConfig, monitoringConfig, null);
    }

    public SerializableFlowConfig(long timeout, RetryPolicy retryPolicy, LLMConfig llmConfig,
                                   MonitoringConfig monitoringConfig, List<String> allowedComponents) {
        this.timeout = timeout;
        this.retryPolicy = retryPolicy;
        this.llmConfig = llmConfig;
        this.monitoringConfig = monitoringConfig;
        this.allowedComponents = allowedComponents;
    }

    public static SerializableFlowConfig from(FlowConfiguration config) {
        // RetryPolicy has Set<Class<? extends Throwable>> which isn't easily serializable
        RetryPolicy rp = config.getRetryPolicy();
        RetryPolicy serializableRp = rp != null
                ? new RetryPolicy(rp.maxAttempts(), rp.delay(), rp.multiplier(), Set.of())
                : null;

        // FlowConfiguration não modela o escopo de componentes; só uma configuração que já veio do
        // documento (este POJO) o carrega adiante.
        List<String> allowed = config instanceof SerializableFlowConfig sfc ? sfc.getAllowedComponents() : null;
        return new SerializableFlowConfig(
                config.getTimeout(),
                serializableRp,
                config.getLLMConfig(),
                config.getMonitoringConfig(),
                allowed
        );
    }

    @Override public long getTimeout() { return timeout; }
    @Override public RetryPolicy getRetryPolicy() { return retryPolicy; }
    @Override @JsonProperty("llmConfig") public LLMConfig getLLMConfig() { return llmConfig; }
    @Override public MonitoringConfig getMonitoringConfig() { return monitoringConfig; }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public List<String> getAllowedComponents() { return allowedComponents; }

    /**
     * Mesmo comportamento do default de {@link FlowConfiguration} (deriva de {@code llmConfig}); o
     * override existe só para o {@code @JsonIgnore}. O archflow-model não depende do Jackson, então a
     * anotação não pode ficar na interface — sem ela o YAML ganhava um {@code llmpatch:} derivado.
     */
    @JsonIgnore
    @Override
    public LLMConfigPatch getLLMPatch() {
        return FlowConfiguration.super.getLLMPatch();
    }

    public void setTimeout(long timeout) { this.timeout = timeout; }
    public void setRetryPolicy(RetryPolicy retryPolicy) { this.retryPolicy = retryPolicy; }
    @JsonProperty("llmConfig")
    public void setLlmConfig(LLMConfig llmConfig) { this.llmConfig = llmConfig; }
    public void setMonitoringConfig(MonitoringConfig monitoringConfig) { this.monitoringConfig = monitoringConfig; }
    public void setAllowedComponents(List<String> allowedComponents) {
        this.allowedComponents = allowedComponents;
    }
}
