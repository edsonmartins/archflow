package br.com.archflow.model.config;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Patch parcial de configuração de LLM. Cada nível da cadeia de resolução
 * (tenant, flow, agente, step) só preenche os campos que deseja sobrescrever;
 * os demais ficam vazios e herdam do pai.
 *
 * <p>Resolução: {@code patch.applyOver(parentResolvido)} produz um
 * {@link ResolvedLLMConfig} em que os campos presentes neste patch vencem os do
 * pai. {@code additionalConfig} faz merge raso (override por chave).
 *
 * @see ResolvedLLMConfig
 * @since 1.0.0
 */
public record LLMConfigPatch(
        Optional<String> provider,
        Optional<String> model,
        OptionalDouble temperature,
        OptionalInt maxTokens,
        OptionalLong timeout,
        Map<String, Object> additionalConfig
) {
    public LLMConfigPatch {
        provider = provider == null ? Optional.empty() : provider;
        model = model == null ? Optional.empty() : model;
        temperature = temperature == null ? OptionalDouble.empty() : temperature;
        maxTokens = maxTokens == null ? OptionalInt.empty() : maxTokens;
        timeout = timeout == null ? OptionalLong.empty() : timeout;
        additionalConfig = additionalConfig == null ? Map.of() : Map.copyOf(additionalConfig);
    }

    private static final LLMConfigPatch EMPTY = new LLMConfigPatch(
            Optional.empty(), Optional.empty(), OptionalDouble.empty(),
            OptionalInt.empty(), OptionalLong.empty(), Map.of());

    /** Patch vazio — não sobrescreve nada (herança transparente). */
    public static LLMConfigPatch empty() {
        return EMPTY;
    }

    /**
     * Constrói um patch a partir de um mapa de configuração (config de step salva
     * pela UI, {@code ComponentMetadata.properties}, etc.). Lê as chaves
     * {@code provider, model, temperature, maxTokens, timeout, additionalConfig};
     * ignora as demais. Numéricos aceitam {@link Number} ou String parseável.
     */
    public static LLMConfigPatch fromMap(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return empty();
        }
        Builder b = builder();
        if (config.get("provider") instanceof String s && !s.isBlank()) {
            b.provider(s);
        }
        if (config.get("model") instanceof String s && !s.isBlank()) {
            b.model(s);
        }
        Double temperature = asDouble(config.get("temperature"));
        if (temperature != null) {
            b.temperature(temperature);
        }
        Integer maxTokens = asInt(config.get("maxTokens"));
        if (maxTokens != null) {
            b.maxTokens(maxTokens);
        }
        Long timeout = asLong(config.get("timeout"));
        if (timeout != null) {
            b.timeout(timeout);
        }
        if (config.get("additionalConfig") instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> am = (Map<String, Object>) m;
            b.additionalConfig(am);
        }
        return b.build();
    }

    private static Double asDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Double.parseDouble(s.trim()); } catch (NumberFormatException ignored) { /* skip */ }
        }
        return null;
    }

    private static Integer asInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { /* skip */ }
        }
        return null;
    }

    private static Long asLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException ignored) { /* skip */ }
        }
        return null;
    }

    public boolean isEmpty() {
        return provider.isEmpty() && model.isEmpty() && temperature.isEmpty()
                && maxTokens.isEmpty() && timeout.isEmpty() && additionalConfig.isEmpty();
    }

    /**
     * Aplica este patch sobre um pai já resolvido. Campos presentes vencem;
     * ausentes herdam. {@code additionalConfig} é mesclado raso (este patch
     * sobrescreve por chave).
     */
    public ResolvedLLMConfig applyOver(ResolvedLLMConfig parent) {
        Map<String, Object> merged = new HashMap<>(parent.additionalConfig());
        merged.putAll(additionalConfig);
        return new ResolvedLLMConfig(
                provider.orElse(parent.provider()),
                model.orElse(parent.model()),
                temperature.orElse(parent.temperature()),
                maxTokens.orElse(parent.maxTokens()),
                timeout.orElse(parent.timeout()),
                merged
        );
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Optional<String> provider = Optional.empty();
        private Optional<String> model = Optional.empty();
        private OptionalDouble temperature = OptionalDouble.empty();
        private OptionalInt maxTokens = OptionalInt.empty();
        private OptionalLong timeout = OptionalLong.empty();
        private final Map<String, Object> additionalConfig = new HashMap<>();

        public Builder provider(String provider) {
            this.provider = Optional.ofNullable(provider);
            return this;
        }

        public Builder model(String model) {
            this.model = Optional.ofNullable(model);
            return this;
        }

        public Builder temperature(double temperature) {
            this.temperature = OptionalDouble.of(temperature);
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = OptionalInt.of(maxTokens);
            return this;
        }

        public Builder timeout(long timeout) {
            this.timeout = OptionalLong.of(timeout);
            return this;
        }

        public Builder additionalConfig(Map<String, Object> additionalConfig) {
            if (additionalConfig != null) {
                this.additionalConfig.putAll(additionalConfig);
            }
            return this;
        }

        public Builder additional(String key, Object value) {
            this.additionalConfig.put(key, value);
            return this;
        }

        public LLMConfigPatch build() {
            return new LLMConfigPatch(provider, model, temperature, maxTokens, timeout, additionalConfig);
        }
    }
}
