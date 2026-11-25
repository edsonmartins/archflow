package br.com.archflow.langchain4j.provider;

import br.com.archflow.model.config.LLMConfigPatch;
import br.com.archflow.model.config.ProviderPreferences;
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
    private final ProviderPreferences providerPreferences;
    private final boolean providerPreferencesRequired;
    private final BaseUrlPolicy baseUrlPolicy;
    private final TenantModelPolicy modelPolicy;

    public DefaultLLMConfigResolver(LLMProviderHub hub) {
        this(hub, TenantKeyResolver.NOOP);
    }

    public DefaultLLMConfigResolver(LLMProviderHub hub, TenantKeyResolver tenantKeyResolver) {
        this(hub, tenantKeyResolver, TierModelResolver.NOOP);
    }

    public DefaultLLMConfigResolver(LLMProviderHub hub, TenantKeyResolver tenantKeyResolver,
                                    TierModelResolver tierModelResolver) {
        this(hub, tenantKeyResolver, tierModelResolver, ProviderPreferences.NONE, false);
    }

    /**
     * @param providerPreferences objeto {@code provider} (ZDR / coleta de dados) enviado em toda
     *                            chamada ao OpenRouter; default do deployment
     *                            ({@code archflow.llm.provider-preferences.*})
     * @param providerPreferencesRequired com o provedor OpenRouter e a preferência vazia, a criação
     *                            do modelo <b>falha</b> em vez de mandar a requisição sem ela
     */
    public DefaultLLMConfigResolver(LLMProviderHub hub, TenantKeyResolver tenantKeyResolver,
                                    TierModelResolver tierModelResolver,
                                    ProviderPreferences providerPreferences,
                                    boolean providerPreferencesRequired) {
        this(hub, tenantKeyResolver, tierModelResolver, providerPreferences, providerPreferencesRequired,
                BaseUrlPolicy.officialOnly());
    }

    /**
     * @param baseUrlPolicy para onde uma chave governada (do resolver ou da plataforma) pode ir quando
     *                      um patch de fluxo/agente/passo declara {@code baseUrl}
     */
    public DefaultLLMConfigResolver(LLMProviderHub hub, TenantKeyResolver tenantKeyResolver,
                                    TierModelResolver tierModelResolver,
                                    ProviderPreferences providerPreferences,
                                    boolean providerPreferencesRequired,
                                    BaseUrlPolicy baseUrlPolicy) {
        this(hub, tenantKeyResolver, tierModelResolver, providerPreferences, providerPreferencesRequired,
                baseUrlPolicy, TenantModelPolicy.UNRESTRICTED);
    }

    /**
     * @param modelPolicy modelos que cada tenant pode usar ({@code allowedModels}); conferida no modelo
     *                    <b>final</b> da cadeia (depois do tier e de todos os patches), antes de criá-lo
     */
    public DefaultLLMConfigResolver(LLMProviderHub hub, TenantKeyResolver tenantKeyResolver,
                                    TierModelResolver tierModelResolver,
                                    ProviderPreferences providerPreferences,
                                    boolean providerPreferencesRequired,
                                    BaseUrlPolicy baseUrlPolicy,
                                    TenantModelPolicy modelPolicy) {
        this.hub = hub;
        this.tenantKeyResolver = tenantKeyResolver != null ? tenantKeyResolver : TenantKeyResolver.NOOP;
        this.tierModelResolver = tierModelResolver != null ? tierModelResolver : TierModelResolver.NOOP;
        this.providerPreferences = providerPreferences != null ? providerPreferences : ProviderPreferences.NONE;
        this.providerPreferencesRequired = providerPreferencesRequired;
        this.baseUrlPolicy = baseUrlPolicy != null ? baseUrlPolicy : BaseUrlPolicy.officialOnly();
        this.modelPolicy = modelPolicy != null ? modelPolicy : TenantModelPolicy.UNRESTRICTED;
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

    @Override
    public Optional<ProviderEndpoint> resolveEndpoint(LLMResolutionRequest request) {
        ResolvedLLMConfig resolved = resolve(request);
        if (resolved.provider() == null || resolved.provider().isBlank()) {
            return Optional.empty();
        }
        Chave chave = resolverChave(request, resolved.provider(), resolved);
        exigirDestinoPermitido(request, resolved, chave);
        LLMProviderConfig config = toProviderConfig(resolved, chave.valor());
        String baseUrl = LLMProviderHub.effectiveBaseUrl(config);
        return baseUrl == null || baseUrl.isBlank()
                ? Optional.empty()
                : Optional.of(new ProviderEndpoint(resolved.provider(), baseUrl, config.getApiKey()));
    }

    /** Resolves config + key, registers it with the hub, and returns the cache id. */
    private String registerConfigFor(LLMResolutionRequest request) {
        ResolvedLLMConfig resolved = resolve(request);
        String provider = resolved.provider();
        if (provider == null || provider.isBlank()) {
            throw new IllegalStateException(
                    "Resolved LLM config has no provider; cannot build a ChatModel");
        }
        // A restrição do tenant vale para o modelo que vai de fato: o do tier, o do patch do nó ou o da
        // plataforma. Conferir só o que o painel mostra deixaria o YAML/API escolher qualquer um.
        modelPolicy.check(request.tenantId(), provider, resolved.model());

        Chave chave = resolverChave(request, provider, resolved);
        exigirDestinoPermitido(request, resolved, chave);
        String apiKey = chave.valor();
        LLMProviderConfig providerConfig = toProviderConfig(resolved, apiKey);
        exigirPreferenciaSeObrigatoria(providerConfig);
        String configId = cacheConfigId(request.tenantId(), resolved, apiKey);

        log.debug("[LLM-RESOLVE] tenant={}, provider={}, model={}, maxTokens={}",
                request.tenantId(), provider, resolved.model(), resolved.maxTokens());

        hub.registerConfig(configId, providerConfig);
        return configId;
    }

    /**
     * Com o deployment exigente, um modelo OpenRouter sem preferência de provedor não é criado.
     *
     * <p>Falhar alto é o requisito. O modo de falha que isto evita é silencioso por natureza: sem o
     * campo, o OpenRouter roteia por qualquer provedor que a conta permita, e a resposta chega igual
     * — nenhum erro, nenhum log, nenhum número errado. Só um consumidor que quer ZDR liga o
     * {@code required}; os outros não pagam nada.
     */
    private void exigirPreferenciaSeObrigatoria(LLMProviderConfig config) {
        if (providerPreferencesRequired
                && config.getProvider() == LLMProvider.OPENROUTER
                && config.getProviderPreferences().isEmpty()) {
            throw new IllegalStateException("[LLM-ZDR] archflow.llm.provider-preferences.required=true, "
                    + "mas nenhuma preferencia de provedor esta configurada para o OpenRouter "
                    + "(archflow.llm.provider-preferences.zdr / .data-collection). Modelo "
                    + config.getModelId() + " NAO criado — a requisicao iria sem a garantia.");
        }
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

    /** A chave e se ela é da governança (resolver/plataforma) ou do próprio fluxo. */
    record Chave(String valor, boolean governada) {
        static final Chave NENHUMA = new Chave(null, false);
    }

    /**
     * Como {@link #resolveApiKey}, mas sabendo de onde a chave veio.
     *
     * <p>O {@code archflow.llm.api-key} chega aqui como {@code additionalConfig.apiKey} do default da
     * plataforma — indistinguível de uma chave inline, a não ser comparando com o default. Comparar
     * serve a duas coisas: ela é governada (o destino passa pela {@link BaseUrlPolicy}), e ela foi
     * cadastrada para o provider da plataforma — um patch que troca o provider não a leva junto
     * para outro fornecedor.
     */
    Chave resolverChave(LLMResolutionRequest request, String provider, ResolvedLLMConfig resolved) {
        Optional<String> doResolver = tenantKeyResolver.resolveApiKey(request.tenantId(), provider);
        if (doResolver.isPresent() && !doResolver.get().isBlank()) {
            return new Chave(doResolver.get(), true);
        }
        Object inline = resolved.additionalConfig().get("apiKey");
        if (inline == null || inline.toString().isBlank()) {
            return Chave.NENHUMA;
        }
        ResolvedLLMConfig plataforma = request.platformDefault();
        Object daPlataforma = plataforma == null ? null : plataforma.additionalConfig().get("apiKey");
        if (daPlataforma != null && daPlataforma.toString().equals(inline.toString())) {
            return provider.equalsIgnoreCase(plataforma.provider())
                    ? new Chave(inline.toString(), true)
                    : Chave.NENHUMA;
        }
        return new Chave(inline.toString(), false);
    }

    /**
     * Chave governada só vai para o endereço oficial do provider, para o {@code archflow.llm.base-url}
     * do operador ou para a lista {@code archflow.llm.allowed-base-urls}. Falha aqui, antes de o
     * modelo existir — nenhuma requisição sai.
     */
    private void exigirDestinoPermitido(LLMResolutionRequest request, ResolvedLLMConfig resolved, Chave chave) {
        Object baseUrl = resolved.additionalConfig().get("baseUrl");
        if (!chave.governada() || baseUrl == null || baseUrl.toString().isBlank()) {
            return;
        }
        ResolvedLLMConfig plataforma = request.platformDefault();
        Object doOperador = plataforma == null ? null : plataforma.additionalConfig().get("baseUrl");
        if (doOperador != null && doOperador.toString().equals(baseUrl.toString())) {
            return;
        }
        baseUrlPolicy.check(resolved.provider(), baseUrl.toString(), "baseUrl");
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
                .cachePrompt(resolved.cachePrompt())
                .extraParams(extra);

        if (apiKey != null && !apiKey.isBlank()) {
            builder.apiKey(apiKey);
        }
        if (resolved.maxTokens() > 0) {
            builder.maxTokens(resolved.maxTokens());
        }
        // O reasoning e o UNICO campo que atravessa daqui ate o corpo da
        // requisicao. O extraParams nao atravessa — ele fica no LLMProviderConfig
        // e so e lido por quem o consulta por chave, nunca pelo builder do modelo.
        resolved.reasoning().ifPresent(builder::reasoning);
        // Default do deployment, nao do no: governanca de privacidade nao pode
        // ser desligada por um campo num fluxo (mesma regra da chave de API). O
        // hub so o envia quando o provedor e OpenRouter.
        builder.providerPreferences(providerPreferences);
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
                // O modelo e construido de forma diferente com e sem a marcacao de
                // cache; slots separados evitam que os dois usos disputem um so.
                + ":" + (resolved.cachePrompt() ? "cache" : "nocache")
                + ":" + keyHash;
    }
}
