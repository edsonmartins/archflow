package br.com.archflow.model.config;

import java.util.Map;
import java.util.Optional;

/**
 * Configuração de LLM totalmente resolvida — resultado de aplicar a cadeia de
 * {@link LLMConfigPatch} sobre um default da plataforma.
 *
 * <p>Diferente de {@link LLMConfigPatch} (parcial, campos opcionais), aqui todos
 * os campos têm valor concreto. É o objeto consumido pelo resolver para produzir
 * o modelo efetivo.
 *
 * @param provider         identificador do provider (ex.: "openai", "ollama"); pode ser {@code null}
 *                         quando a config legada não o define
 * @param model            identificador do modelo
 * @param temperature      temperatura de geração
 * @param maxTokens        máximo de tokens na resposta
 * @param timeout          timeout da chamada LLM, em segundos
 * @param additionalConfig parâmetros extras (ex.: {@code baseUrl}); imutável
 * @param reasoning        objeto {@code reasoning} do provedor, quando declarado;
 *                         a forma não é interpretada aqui — ver {@link LLMConfigPatch}
 * @since 1.0.0
 */
public record ResolvedLLMConfig(
        String provider,
        String model,
        double temperature,
        int maxTokens,
        long timeout,
        Map<String, Object> additionalConfig,
        Optional<Map<String, Object>> reasoning
) {
    public ResolvedLLMConfig {
        additionalConfig = additionalConfig == null ? Map.of() : Map.copyOf(additionalConfig);
        reasoning = reasoning == null ? Optional.empty() : reasoning.map(Map::copyOf);
    }

    /** Compat: config sem {@code reasoning} — a forma que existia antes. */
    public ResolvedLLMConfig(String provider, String model, double temperature, int maxTokens,
                             long timeout, Map<String, Object> additionalConfig) {
        this(provider, model, temperature, maxTokens, timeout, additionalConfig, Optional.empty());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String provider;
        private String model;
        private double temperature = 0.7;
        private int maxTokens = 0;
        private long timeout = 0L;
        private Map<String, Object> additionalConfig = Map.of();
        private Optional<Map<String, Object>> reasoning = Optional.empty();

        /** Objeto {@code reasoning} do provedor; {@code null} = nenhum. */
        public Builder reasoning(Map<String, Object> reasoning) {
            this.reasoning = Optional.ofNullable(reasoning);
            return this;
        }

        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder temperature(double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder timeout(long timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder additionalConfig(Map<String, Object> additionalConfig) {
            if (additionalConfig != null) {
                this.additionalConfig = additionalConfig;
            }
            return this;
        }

        public ResolvedLLMConfig build() {
            return new ResolvedLLMConfig(provider, model, temperature, maxTokens, timeout,
                    additionalConfig, reasoning);
        }
    }
}
