package br.com.archflow.agent.config;

import br.com.archflow.model.enums.LogLevel;

import java.util.Map;

/**
 * Configuração do ArchFlow Agent
 */
public record AgentConfig(
    // Configurações básicas
    String agentId,
    String pluginsPath,
    
    // Configurações de execução
    int maxConcurrentFlows,
    long defaultFlowTimeout,
    RetryConfig retryConfig,
    
    // Configurações de recursos
    ResourceConfig resourceConfig,
    
    // Configurações de monitoramento
    MonitoringConfig monitoringConfig,
    
    // Configurações extras
    Map<String, Object> extraConfig
) {
    /**
     * Builder para facilitar a criação da configuração
     */
    public static class Builder {
        private String agentId = "default";
        private String pluginsPath = "plugins";
        private int maxConcurrentFlows = 10;
        private long defaultFlowTimeout = 3600000; // 1 hora
        private RetryConfig retryConfig = new RetryConfig(3, 1000, 2.0);
        private ResourceConfig resourceConfig = new ResourceConfig(
            Runtime.getRuntime().availableProcessors(),
            Runtime.getRuntime().maxMemory() / 2
        );
        private MonitoringConfig monitoringConfig = new MonitoringConfig(
            true,  // metrics enabled
            LogLevel.INFO,
            300,   // metrics interval (5 min)
            Map.of()
        );
        private Map<String, Object> extraConfig = Map.of();

        public Builder agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }

        public Builder pluginsPath(String pluginsPath) {
            this.pluginsPath = pluginsPath;
            return this;
        }

        public Builder maxConcurrentFlows(int maxConcurrentFlows) {
            this.maxConcurrentFlows = maxConcurrentFlows;
            return this;
        }

        public Builder defaultFlowTimeout(long defaultFlowTimeout) {
            this.defaultFlowTimeout = defaultFlowTimeout;
            return this;
        }

        public Builder retryConfig(RetryConfig retryConfig) {
            this.retryConfig = retryConfig;
            return this;
        }

        public Builder resourceConfig(ResourceConfig resourceConfig) {
            this.resourceConfig = resourceConfig;
            return this;
        }

        public Builder monitoringConfig(MonitoringConfig monitoringConfig) {
            this.monitoringConfig = monitoringConfig;
            return this;
        }

        public Builder extraConfig(Map<String, Object> extraConfig) {
            this.extraConfig = extraConfig;
            return this;
        }

        public AgentConfig build() {
            return new AgentConfig(
                agentId,
                pluginsPath,
                maxConcurrentFlows,
                defaultFlowTimeout,
                retryConfig,
                resourceConfig,
                monitoringConfig,
                extraConfig
            );
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}

