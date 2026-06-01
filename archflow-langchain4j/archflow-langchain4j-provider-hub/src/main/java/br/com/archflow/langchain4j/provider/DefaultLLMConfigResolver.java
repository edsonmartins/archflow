package br.com.archflow.langchain4j.provider;

import br.com.archflow.model.config.ResolvedLLMConfig;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Implementação padrão do {@link LLMConfigResolver}. Generaliza o
 * {@code DynamicChatModelResolver} do gestor-rq: cadeia de herança explícita,
 * chave por tenant (via {@link TenantKeyResolver}) e cache do modelo por config
 * efetiva (delegado ao {@link LLMProviderHub}).
 *
 * @since 1.0.0
 */
public class DefaultLLMConfigResolver implements LLMConfigResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultLLMConfigResolver.class);

    private final LLMProviderHub hub;
    private final TenantKeyResolver tenantKeyResolver;

    public DefaultLLMConfigResolver(LLMProviderHub hub) {
        this(hub, TenantKeyResolver.NOOP);
    }

    public DefaultLLMConfigResolver(LLMProviderHub hub, TenantKeyResolver tenantKeyResolver) {
        this.hub = hub;
        this.tenantKeyResolver = tenantKeyResolver != null ? tenantKeyResolver : TenantKeyResolver.NOOP;
    }

    @Override
    public ResolvedLLMConfig resolve(LLMResolutionRequest request) {
        // Precedência: platform < tenant < flow < agent < step (último aplicado vence).
        ResolvedLLMConfig acc = request.platformDefault();
        acc = request.tenantDefault().applyOver(acc);
        acc = request.flowPatch().applyOver(acc);
        acc = request.agentPatch().applyOver(acc);
        acc = request.stepPatch().applyOver(acc);
        return acc;
    }

    @Override
    public ChatModel resolveModel(LLMResolutionRequest request) {
        ResolvedLLMConfig resolved = resolve(request);
        String provider = resolved.provider();
        if (provider == null || provider.isBlank()) {
            throw new IllegalStateException(
                    "Resolved LLM config has no provider; cannot build a ChatModel");
        }

        String apiKey = resolveApiKey(request.tenantId(), provider, resolved);
        LLMProviderConfig providerConfig = toProviderConfig(resolved, apiKey);
        String configId = cacheConfigId(request.tenantId(), resolved, apiKey);

        log.debug("[LLM-RESOLVE] tenant={}, provider={}, model={}, maxTokens={}",
                request.tenantId(), provider, resolved.model(), resolved.maxTokens());

        hub.registerConfig(configId, providerConfig);
        return hub.getModel(configId);
    }

    /** Chave por tenant {@literal >} chave inline em {@code additionalConfig.apiKey} {@literal >} nenhuma. */
    String resolveApiKey(String tenantId, String provider, ResolvedLLMConfig resolved) {
        Optional<String> tenantKey = tenantKeyResolver.resolveApiKey(tenantId, provider);
        if (tenantKey.isPresent() && !tenantKey.get().isBlank()) {
            return tenantKey.get();
        }
        Object inlineKey = resolved.additionalConfig().get("apiKey");
        return inlineKey != null ? inlineKey.toString() : null;
    }

    /**
     * Traduz uma {@link ResolvedLLMConfig} em {@link LLMProviderConfig}.
     * Visível ao pacote para testes (não cria modelo real).
     */
    LLMProviderConfig toProviderConfig(ResolvedLLMConfig resolved, String apiKey) {
        LLMProvider providerEnum = LLMProvider.fromId(resolved.provider())
                .orElseThrow(() -> new IllegalArgumentException("Unknown provider: " + resolved.provider()));

        Map<String, Object> extra = new HashMap<>(resolved.additionalConfig());
        extra.remove("apiKey");   // tratado explicitamente
        extra.remove("baseUrl");  // tratado explicitamente

        LLMProviderConfig.Builder builder = LLMProviderConfig.builder()
                .provider(providerEnum)
                .modelId(resolved.model())
                .temperature(resolved.temperature())
                .extraParams(extra);

        if (apiKey != null && !apiKey.isBlank()) {
            builder.apiKey(apiKey);
        }
        if (resolved.maxTokens() > 0) {
            builder.maxTokens(resolved.maxTokens());
        }
        if (resolved.timeout() > 0) {
            builder.timeout(Duration.ofMillis(resolved.timeout()));
        }
        Object baseUrl = resolved.additionalConfig().get("baseUrl");
        if (baseUrl != null) {
            builder.baseUrl(baseUrl.toString());
        }
        return builder.build();
    }

    /**
     * Slot de cache: configs efetivas distintas (provider/model/maxTokens/chave)
     * ocupam slots distintos no hub. A chave entra como hash, nunca em claro
     * (mesmo cuidado do resolver do gestor-rq).
     */
    String cacheConfigId(String tenantId, ResolvedLLMConfig resolved, String apiKey) {
        int keyHash = apiKey != null ? apiKey.hashCode() : 0;
        return (tenantId != null ? tenantId : "__global__")
                + ":" + resolved.provider()
                + ":" + resolved.model()
                + ":" + resolved.maxTokens()
                + ":" + keyHash;
    }
}
