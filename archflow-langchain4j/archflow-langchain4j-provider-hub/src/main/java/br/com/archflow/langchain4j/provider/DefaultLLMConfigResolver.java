package br.com.archflow.langchain4j.provider;

import br.com.archflow.model.config.LLMConfigPatch;
import br.com.archflow.model.config.ResolvedLLMConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
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
    private final TierModelResolver tierModelResolver;

    public DefaultLLMConfigResolver(LLMProviderHub hub) {
        this(hub, TenantKeyResolver.NOOP);
    }

    public DefaultLLMConfigResolver(LLMProviderHub hub, TenantKeyResolver tenantKeyResolver) {
        this(hub, tenantKeyResolver, TierModelResolver.NOOP);
    }

    public DefaultLLMConfigResolver(LLMProviderHub hub, TenantKeyResolver tenantKeyResolver,
                                    TierModelResolver tierModelResolver) {
        this.hub = hub;
        this.tenantKeyResolver = tenantKeyResolver != null ? tenantKeyResolver : TenantKeyResolver.NOOP;
        this.tierModelResolver = tierModelResolver != null ? tierModelResolver : TierModelResolver.NOOP;
    }

    @Override
    public ResolvedLLMConfig resolve(LLMResolutionRequest request) {
        // Precedência: platform < TIER < tenant < flow < agent < step (último aplicado vence).
        //
        // O tier entra logo acima do default da plataforma, e não no topo, porque
        // ele é a política GENÉRICA ("esta tarefa é difícil") e os patches são
        // decisões específicas sobre aquele fluxo, agente ou passo. Um modelo
        // declarado num nó foi escolhido por alguém que sabia o que estava
        // fazendo; deixá-lo perder para um tier surpreenderia — e romperia a
        // precedência documentada no LLMResolutionRequest.
        ResolvedLLMConfig acc = request.platformDefault();
        String modeloDoTier = aplicarTier(request, acc);
        if (modeloDoTier != null) {
            // Como patch, e não construindo outra config: reusa a mesma máquina de
            // precedência do resto da cadeia, então o tier se comporta como
            // qualquer outro nível em vez de ser um caso especial.
            acc = LLMConfigPatch.builder().model(modeloDoTier).build().applyOver(acc);
        }
        acc = request.tenantDefault().applyOver(acc);
        acc = request.flowPatch().applyOver(acc);
        acc = request.agentPatch().applyOver(acc);
        acc = request.stepPatch().applyOver(acc);
        avisarSeTierNaoFoiHonrado(request, modeloDoTier, acc);
        return acc;
    }

    /** O modelo mapeado para o tier pedido, ou {@code null} quando não há tier ou mapa. */
    private String aplicarTier(LLMResolutionRequest request, ResolvedLLMConfig platformDefault) {
        if (request.tier() == null) {
            return null;
        }
        try {
            return tierModelResolver.resolveModel(request.tenantId(), request.tier()).orElse(null);
        } catch (RuntimeException e) {
            // Um resolvedor de produto que estoura não pode derrubar a execução:
            // o pior caso aqui é cair no comportamento anterior, que funcionava.
            log.warn("[LLM-TIER] resolvedor de tier falhou para tenant={} tier={}: {} "
                    + "— seguindo com a cadeia de patches", request.tenantId(), request.tier(),
                    e.toString());
            return null;
        }
    }

    /**
     * O tier foi pedido e o modelo final não é o dele — diga isso.
     *
     * <p>Este log é metade da correção, não um extra. O {@code tier} do invoke
     * era <b>descartado sem deixar rastro</b>: o Playbook decidia
     * {@code STRONG}, o resolvedor entregava o modelo do tenant, e nada no
     * sistema registrava a discordância. Ficou assim dois dias, e o contorno foi
     * declarar o modelo direto no nó do fluxo — o que faz a política virar
     * decoração e obriga quem decide política a conhecer nome de modelo de
     * provedor.
     *
     * <p>Duas causas distintas, mensagens distintas: não existe mapa para o
     * tier, ou existe e algo mais específico venceu. A primeira se resolve
     * cadastrando o mapa; a segunda é legítima e só precisa ser visível.
     */
    private void avisarSeTierNaoFoiHonrado(LLMResolutionRequest request, String modeloDoTier,
                                           ResolvedLLMConfig resolvido) {
        if (request.tier() == null) {
            return;
        }
        if (modeloDoTier == null) {
            log.warn("[LLM-TIER] tier={} pedido para tenant={} e NAO honrado: nenhum mapa "
                            + "tier->modelo para este tenant. Modelo em uso: {} (da cadeia de patches). "
                            + "Cadastre o mapa num TierModelResolver para a politica ter efeito.",
                    request.tier(), request.tenantId(), resolvido.model());
            return;
        }
        if (!modeloDoTier.equals(resolvido.model())) {
            log.warn("[LLM-TIER] tier={} mapeia para {} no tenant={}, mas o modelo final e {} "
                            + "— um patch mais especifico (tenant/flow/agent/step) venceu. "
                            + "Se a intencao era decidir por tier, remova o modelo declarado.",
                    request.tier(), modeloDoTier, request.tenantId(), resolvido.model());
        }
    }

    @Override
    public ChatModel resolveModel(LLMResolutionRequest request) {
        return hub.getModel(registerConfigFor(request));
    }

    @Override
    public StreamingChatModel resolveStreamingModel(LLMResolutionRequest request) {
        return hub.getStreamingModel(registerConfigFor(request));
    }

    /** Resolves config + key, registers it with the hub, and returns the cache id. */
    private String registerConfigFor(LLMResolutionRequest request) {
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
        return configId;
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
            // timeout efetivo é em SEGUNDos (convenção do UI/gestor/integrall).
            builder.timeout(Duration.ofSeconds(resolved.timeout()));
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
