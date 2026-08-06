package br.com.archflow.model.config;

import java.util.HashMap;
import java.util.LinkedHashMap;
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
        Map<String, Object> additionalConfig,
        Optional<Map<String, Object>> reasoning
) {
    public LLMConfigPatch {
        provider = provider == null ? Optional.empty() : provider;
        model = model == null ? Optional.empty() : model;
        temperature = temperature == null ? OptionalDouble.empty() : temperature;
        maxTokens = maxTokens == null ? OptionalInt.empty() : maxTokens;
        timeout = timeout == null ? OptionalLong.empty() : timeout;
        additionalConfig = additionalConfig == null ? Map.of() : Map.copyOf(additionalConfig);
        reasoning = reasoning == null ? Optional.empty() : reasoning.map(Map::copyOf);
    }

    /**
     * Compat: patch sem {@code reasoning} — a forma que existia antes.
     *
     * <p>Um record de configuração que ganha campo sem construtor de
     * compatibilidade quebra todo chamador que não tem nada a ver com a mudança,
     * e este é construído em muitos lugares.
     */
    public LLMConfigPatch(Optional<String> provider, Optional<String> model,
                          OptionalDouble temperature, OptionalInt maxTokens,
                          OptionalLong timeout, Map<String, Object> additionalConfig) {
        this(provider, model, temperature, maxTokens, timeout, additionalConfig, Optional.empty());
    }

    private static final LLMConfigPatch EMPTY = new LLMConfigPatch(
            Optional.empty(), Optional.empty(), OptionalDouble.empty(),
            OptionalInt.empty(), OptionalLong.empty(), Map.of(), Optional.empty());

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
        Map<String, Object> reasoning = lerReasoning(config.get("reasoning"));
        if (reasoning != null) {
            b.reasoning(reasoning);
        }
        return b.build();
    }

    /**
     * Lê e valida o {@code reasoning} do nó.
     *
     * <p><b>Falha em vez de ignorar.</b> As demais chaves deste parser são
     * tolerantes — um {@code maxTokens} ilegível simplesmente não entra no patch,
     * e o default vale. Para {@code reasoning} isso seria péssimo: quem o declara
     * está tentando cortar tempo e teto de tokens, e um valor mal escrito
     * silenciosamente ignorado devolveria exatamente o comportamento que se quis
     * evitar — com o operador convencido de que resolveu.
     *
     * <p><b>A forma não é interpretada.</b> O OpenRouter aceita
     * {@code {"effort": "..."} }, {@code {"max_tokens": N} } e
     * {@code {"exclude": true} }, e a documentação é ambígua sobre qual deles o
     * Gemini 2.5 honra. Validar a semântica aqui seria fixar um palpite: o objeto
     * declarado viaja como veio, e o provedor é a autoridade sobre o conteúdo.
     * O que se valida é a <i>estrutura</i> — que é objeto, que não é vazio, e que
     * as chaves conhecidas têm o tipo certo.
     *
     * <p>Uma armadilha que vale registrar: {@code exclude: true} apenas esconde o
     * raciocínio da resposta, <b>não o evita</b>. Não economiza tempo nem token.
     * É a opção que mais parece resolver e a que menos resolve.
     *
     * @return o mapa validado, ou {@code null} quando a chave não foi declarada
     * @throws IllegalArgumentException quando declarada e malformada
     */
    private static Map<String, Object> lerReasoning(Object valor) {
        if (valor == null) {
            return null;
        }
        if (!(valor instanceof Map<?, ?> mapa)) {
            throw new IllegalArgumentException(
                    "reasoning deve ser um objeto (ex.: {\"effort\": \"none\"}), e veio "
                            + valor.getClass().getSimpleName() + ": " + valor);
        }
        if (mapa.isEmpty()) {
            throw new IllegalArgumentException(
                    "reasoning veio vazio — declare {\"effort\": ...}, {\"max_tokens\": N} ou "
                            + "{\"exclude\": true}, ou remova a chave");
        }
        Map<String, Object> resultado = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> e : mapa.entrySet()) {
            String chave = String.valueOf(e.getKey());
            Object v = e.getValue();
            switch (chave) {
                case "effort" -> {
                    if (!(v instanceof String s) || s.isBlank()) {
                        throw new IllegalArgumentException(
                                "reasoning.effort deve ser texto não vazio (ex.: \"none\", "
                                        + "\"minimal\", \"low\"), e veio: " + v);
                    }
                    resultado.put(chave, s.trim());
                }
                case "max_tokens" -> {
                    Integer n = asInt(v);
                    if (n == null || n < 0) {
                        throw new IllegalArgumentException(
                                "reasoning.max_tokens deve ser inteiro não negativo, e veio: " + v);
                    }
                    resultado.put(chave, n);
                }
                case "exclude" -> {
                    if (!(v instanceof Boolean b)) {
                        throw new IllegalArgumentException(
                                "reasoning.exclude deve ser booleano, e veio: " + v);
                    }
                    resultado.put(chave, b);
                }
                // Chave desconhecida passa. O provedor evolui mais rápido que este
                // parser, e recusar o que ele talvez já aceite trocaria um erro
                // silencioso por um bloqueio arbitrário.
                default -> resultado.put(chave, v);
            }
        }
        return resultado;
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
                && maxTokens.isEmpty() && timeout.isEmpty() && additionalConfig.isEmpty()
                && reasoning.isEmpty();
    }

    /**
     * Forma canônica serializável do patch. Omite campos ausentes para que o
     * documento continue representando herança, e não defaults acidentais.
     * Credenciais de provider devem continuar no resolver do tenant; portanto,
     * chamadores não devem colocá-las em {@code additionalConfig}.
     */
    public Map<String, Object> toMap() {
        if (isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        provider.ifPresent(value -> out.put("provider", value));
        model.ifPresent(value -> out.put("model", value));
        if (temperature.isPresent()) out.put("temperature", temperature.getAsDouble());
        if (maxTokens.isPresent()) out.put("maxTokens", maxTokens.getAsInt());
        if (timeout.isPresent()) out.put("timeout", timeout.getAsLong());
        if (!additionalConfig.isEmpty()) out.put("additionalConfig", additionalConfig);
        reasoning.ifPresent(value -> out.put("reasoning", value));
        return Map.copyOf(out);
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
                merged,
                // Substitui, e nao mescla: as formas do reasoning sao ALTERNATIVAS
                // ({effort} vs {max_tokens} vs {exclude}). Mesclar produziria um
                // objeto com duas intencoes ao mesmo tempo, e quem declarou a mais
                // especifica nao saberia que herdou a outra junto.
                reasoning.or(parent::reasoning)
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
        private Optional<Map<String, Object>> reasoning = Optional.empty();

        /** Objeto {@code reasoning} enviado ao provedor; a forma nao e interpretada. */
        public Builder reasoning(Map<String, Object> reasoning) {
            this.reasoning = Optional.ofNullable(reasoning);
            return this;
        }

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
            return new LLMConfigPatch(provider, model, temperature, maxTokens, timeout,
                    additionalConfig, reasoning);
        }
    }
}
