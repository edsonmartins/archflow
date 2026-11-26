package br.com.archflow.standalone;

import br.com.archflow.decision.DecisionKeys;
import br.com.archflow.langchain4j.provider.BaseUrlPolicy;
import br.com.archflow.langchain4j.provider.PropertyTenantKeyResolver;
import br.com.archflow.model.config.ProviderPreferences;
import br.com.archflow.model.config.ResolvedLLMConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A configuração de LLM de uma execução sem servidor, lida <b>só do ambiente</b> (RCHFLW-37).
 *
 * <p>O servidor lê {@code archflow.llm.*} das propriedades; fora dele não há Spring, então os mesmos nomes
 * vêm das variáveis de ambiente que o relaxed binding do servidor já aceita — quem configurou o
 * deployment reconhece cada uma:</p>
 * <ul>
 *   <li>{@code ARCHFLOW_LLM_PROVIDER} / {@code ARCHFLOW_LLM_MODEL} — o padrão de LLM dos nós de chat que
 *       não dizem provider (o {@code archflow.llm.provider/model});</li>
 *   <li>{@code ARCHFLOW_LLM_API_KEY} — a chave desse provider, e só dele;</li>
 *   <li>{@code ARCHFLOW_LLM_KEYS_<PROVIDER>} — a chave de cada provider ({@code ARCHFLOW_LLM_KEYS_OPENAI});</li>
 *   <li>{@code ARCHFLOW_LLM_BASE_URL} — o endereço do provider padrão (um gateway);</li>
 *   <li>{@code ARCHFLOW_LLM_ALLOWED_BASE_URLS} — outros destinos que podem receber a chave (vírgulas);</li>
 *   <li>{@code ARCHFLOW_LLM_PROVIDER_PREFERENCES_ZDR}, {@code ..._DATA_COLLECTION}, {@code ..._REQUIRED} —
 *       a preferência de provedor do OpenRouter;</li>
 *   <li>{@code ARCHFLOW_DECISION_KEYS_<REF>} — a chave de um provedor do nó {@code decision};</li>
 *   <li>{@code ARCHFLOW_FLOW_LENIENT_CONDITIONS} — {@code true} devolve, numa condition/switch, o ramo padrão para
 *       a referência sem valor (o {@code archflow.flow.lenient-conditions} do servidor, RCHFLW-123).</li>
 * </ul>
 *
 * <p>{@code ARCHFLOW_PROVIDER}, {@code ARCHFLOW_MODEL} e {@code ARCHFLOW_API_KEY} são os nomes que o runner
 * standalone sempre documentou e continuam valendo para os três primeiros; o nome {@code ARCHFLOW_LLM_*}
 * vence quando os dois estão definidos.</p>
 *
 * <p><b>A chave nunca vem do arquivo do fluxo.</b> O runner descarta a que um nó trouxer
 * ({@code PortableFlowStepFactory.setInlineApiKeys(false)}): um arquivo que circula entre máquinas não
 * decide com que credencial o processo fala. Os valores nunca aparecem em {@link #toString()}.</p>
 */
public final class StandaloneEnvironment {

    static final String PROVIDER = "ARCHFLOW_LLM_PROVIDER";
    static final String PROVIDER_SHORT = "ARCHFLOW_PROVIDER";
    static final String MODEL = "ARCHFLOW_LLM_MODEL";
    static final String MODEL_SHORT = "ARCHFLOW_MODEL";
    static final String API_KEY = "ARCHFLOW_LLM_API_KEY";
    static final String API_KEY_SHORT = "ARCHFLOW_API_KEY";
    static final String BASE_URL = "ARCHFLOW_LLM_BASE_URL";
    static final String ALLOWED_BASE_URLS = "ARCHFLOW_LLM_ALLOWED_BASE_URLS";
    static final String KEYS_PREFIX = "ARCHFLOW_LLM_KEYS_";
    static final String PREFERENCES_ZDR = "ARCHFLOW_LLM_PROVIDER_PREFERENCES_ZDR";
    static final String PREFERENCES_DATA_COLLECTION = "ARCHFLOW_LLM_PROVIDER_PREFERENCES_DATA_COLLECTION";
    static final String PREFERENCES_REQUIRED = "ARCHFLOW_LLM_PROVIDER_PREFERENCES_REQUIRED";
    static final String DECISION_KEYS_PREFIX = "ARCHFLOW_DECISION_KEYS_";
    static final String LENIENT_CONDITIONS = "ARCHFLOW_FLOW_LENIENT_CONDITIONS";

    private final Map<String, String> env;
    private final PropertyTenantKeyResolver keyResolver;

    private StandaloneEnvironment(Map<String, String> env) {
        this.env = env == null ? Map.of() : Map.copyOf(env);
        Map<String, String> porProvider = new HashMap<>();
        this.env.forEach((nome, valor) -> {
            if (nome.startsWith(KEYS_PREFIX) && nome.length() > KEYS_PREFIX.length()) {
                porProvider.put(nome.substring(KEYS_PREFIX.length()).toLowerCase(Locale.ROOT), valor);
            }
        });
        // Sem tenant fora do servidor: só as chaves da plataforma, na mesma ordem do servidor.
        this.keyResolver = new PropertyTenantKeyResolver(Map.of(), porProvider,
                value(PROVIDER, PROVIDER_SHORT).orElse(null), value(API_KEY, API_KEY_SHORT).orElse(null));
    }

    /** As variáveis do processo. */
    public static StandaloneEnvironment fromSystem() {
        return of(System.getenv());
    }

    /** Um ambiente dado — para quem monta o processo (e para os testes). */
    public static StandaloneEnvironment of(Map<String, String> env) {
        return new StandaloneEnvironment(env);
    }

    /** As chaves de LLM: por provider, e a padrão só para o provider padrão. */
    public PropertyTenantKeyResolver keyResolver() {
        return keyResolver;
    }

    /** Os oficiais, o {@code ARCHFLOW_LLM_BASE_URL} e os de {@code ARCHFLOW_LLM_ALLOWED_BASE_URLS}. */
    public BaseUrlPolicy baseUrlPolicy() {
        List<String> lista = new ArrayList<>();
        value(ALLOWED_BASE_URLS).ifPresent(v -> lista.addAll(Arrays.asList(v.split(","))));
        value(BASE_URL).ifPresent(lista::add);
        return BaseUrlPolicy.of(lista);
    }

    /**
     * O padrão de LLM dos nós de chat que não dizem provider, ou {@code null} sem {@code ARCHFLOW_LLM_PROVIDER}
     * — aí o nó precisa dizer o provider (ou herdá-lo do fluxo), como no servidor sem padrão.
     */
    public ResolvedLLMConfig platformDefaultLLM() {
        Optional<String> provider = value(PROVIDER, PROVIDER_SHORT);
        if (provider.isEmpty()) {
            return null;
        }
        Map<String, Object> additional = new HashMap<>();
        // A chave não entra aqui: vem do resolver, como no servidor. Só o destino.
        value(BASE_URL).ifPresent(url -> additional.put("baseUrl", url));
        return ResolvedLLMConfig.builder()
                .provider(provider.get())
                .model(value(MODEL, MODEL_SHORT).orElse(null))
                .additionalConfig(additional)
                .build();
    }

    public ProviderPreferences providerPreferences() {
        return ProviderPreferences.of(value(PREFERENCES_ZDR).orElse(""),
                value(PREFERENCES_DATA_COLLECTION).orElse(""));
    }

    public boolean providerPreferencesRequired() {
        return value(PREFERENCES_REQUIRED).map(Boolean::parseBoolean).orElse(false);
    }

    /** Como no servidor: sem a variável, a referência sem valor numa condition/switch falha o passo. */
    public boolean lenientConditions() {
        return value(LENIENT_CONDITIONS).map(Boolean::parseBoolean).orElse(false);
    }

    /**
     * A chave de um provedor de decisão: {@code ARCHFLOW_DECISION_KEYS_<REF>}, depois a chave de LLM da
     * plataforma para aquele provider — a ordem do servidor sem o nível do tenant.
     */
    public DecisionKeys decisionKeys() {
        return (tenantId, ref) -> {
            if (ref == null || ref.isBlank()) {
                return Optional.empty();
            }
            Optional<String> daDecisao = value(DECISION_KEYS_PREFIX
                    + ref.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace('.', '_'));
            return daDecisao.isPresent() ? daDecisao : keyResolver.resolvePlatformKey(ref);
        };
    }

    /** O valor da primeira variável definida e não vazia. */
    private Optional<String> value(String... nomes) {
        for (String nome : nomes) {
            String valor = env.get(nome);
            if (valor != null && !valor.isBlank()) {
                return Optional.of(valor.trim());
            }
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        // Só nomes, nunca valores.
        return "StandaloneEnvironment[provider=" + value(PROVIDER, PROVIDER_SHORT).orElse("-")
                + ", chaves=" + keyResolver.platformProviders() + "]";
    }
}
