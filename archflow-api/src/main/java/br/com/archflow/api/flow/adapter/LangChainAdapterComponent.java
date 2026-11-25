package br.com.archflow.api.flow.adapter;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.langchain4j.core.spi.LangChainRegistry;
import br.com.archflow.langchain4j.openrouter.OpenRouterChatAdapter;
import br.com.archflow.langchain4j.provider.BaseUrlPolicy;
import br.com.archflow.langchain4j.provider.LLMProvider;
import br.com.archflow.langchain4j.provider.TenantKeyResolver;
import br.com.archflow.langchain4j.provider.TenantModelPolicy;
import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.config.ProviderPreferences;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.engine.ExecutionKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Faz um {@link LangChainAdapter} executar como {@link AIComponent} num nó de
 * workflow.
 *
 * <p>Existia uma camada inteira de adapters (~25k LOC, 15 submódulos) que o
 * designer <b>listava</b> e o runtime <b>nunca instanciava</b>: a única
 * referência de produção ao {@link LangChainRegistry} fora daqueles módulos era
 * a listagem do catálogo. Um nó apontando para "openai" falhava em execução com
 * "component not found", porque adapter não é {@code AIComponent}. As duas
 * interfaces são quase idênticas — a ponte que faltava é fina.
 *
 * <h2>Por que a criação é preguiçosa</h2>
 * A factory do adapter chama {@code configure()}, que chama {@code validate()},
 * que <b>exige a chave de API</b>. Criar no momento em que o fluxo é
 * desserializado faria abrir um workflow no designer explodir por falta de
 * chave. O adapter nasce na primeira execução.
 *
 * <h2>Por que o cache é por tenant</h2>
 * A chave vem do {@link TenantKeyResolver}, e o tenant só é conhecido em
 * execução ({@link ExecutionContext#getTenantId()}). Um mesmo objeto de fluxo
 * pode servir tenants diferentes, então guardar um único adapter configurado
 * entregaria a chave de um tenant para outro.
 *
 * <h2>Segredo não mora no workflow</h2>
 * A chave é injetada a partir do resolver do tenant; o JSON do workflow não
 * precisa (e não deve) carregá-la. Uma chave inline no nó continua funcionando
 * para desenvolvimento, mas o resolver tem precedência.
 *
 * <h2>Nem o destino da chave</h2>
 * Com a chave injetada pelo resolver (tenant ou plataforma), um {@code base.url}
 * / {@code baseUrl} / {@code endpoint} no nó só é aceito se for o endereço
 * oficial do provider ou um da lista do operador ({@link BaseUrlPolicy}). Sem
 * isso, quem edita um fluxo apontava o nó para um servidor seu e recebia a chave
 * da plataforma no header {@code Authorization}.
 *
 * <h2>O nó de chat que o designer grava executa</h2>
 * O designer não tem campo de operação, então o motor manda {@code execute} — que
 * nenhum adapter de chat aceita ("Unsupported operation: execute", RCHFLW-51). Para
 * adapters de chat, operação ausente ou {@code execute} vira {@code generate} — uma
 * volta isolada, sem a memória da execução ({@link #chatOperation(String)}) — e a
 * entrada que não é texto — o mapa da entrada do fluxo ou a saída-objeto do passo
 * anterior — vira o texto da mensagem ({@link #messageText(Object)}). Operações
 * explícitas ({@code generate}, {@code chat}) seguem como vieram.
 *
 * <h2>Modelo, chave e destino de um nó de chat não vêm das variáveis</h2>
 * O nó de chat roda com uma visão do contexto sem {@link ExecutionKeys#LLM_MODEL} e
 * {@link ExecutionKeys#LLM_RESOLVED_CONFIG} ({@link ChatNodeContext}). Cada chave da
 * entrada de uma execução vira variável ({@code RunInputSeeding}), e o adapter do
 * OpenRouter lê {@code llm.model} como override: um {@code {"llm.model": "x"}} no
 * {@code POST /execute} escolheria qualquer modelo, pago com a chave governada. O que
 * vale para o nó é nó → fluxo → plataforma ({@link ChatNodeDefaults}), a chave do
 * resolver e o destino da {@link BaseUrlPolicy}.
 *
 * <h2>Nem um modelo fora do que o tenant pode usar</h2>
 * Com {@code allowedModels} no cadastro do tenant, o modelo do nó (e o de fallback, quando há) tem
 * de estar na lista ({@link TenantModelPolicy}, RCHFLW-79). A conferência é a cada execução, antes do
 * adapter em cache: o adapter é por tenant e vive o quanto o fluxo vive, e uma restrição criada
 * depois tem de valer na próxima chamada, não no próximo deploy.
 */
public final class LangChainAdapterComponent implements AIComponent {

    private static final Logger log = LoggerFactory.getLogger(LangChainAdapterComponent.class);

    /** Chave que os adapters esperam para o segredo. */
    static final String API_KEY_PROPERTY = "api.key";
    /** Chave que os adapters esperam para o modelo. */
    static final String MODEL_PROPERTY = "model.name";

    /**
     * Campos de destino que um nó pode trazer. Só o {@code base.url} é lido pelos adapters de
     * chat hoje, mas um adapter novo que leia outro destes não pode nascer com o vazamento.
     */
    static final List<String> URL_PROPERTIES = List.of("base.url", "baseUrl", "endpoint");

    /**
     * Adapters que leem o segredo de outra propriedade que não o {@code api.key}, por
     * {@code provider:tipo}. O embedding da OpenAI lê {@code openai.api.key} (é o que o catálogo
     * pede, {@code CatalogControllerImpl}); injetar a chave governada só no {@code api.key} a deixaria
     * de fora, e exigir o {@code api.key} quebraria o nó que traz a sua.
     */
    static final Map<String, String> KEY_PROPERTY_BY_ADAPTER = Map.of("openai:embedding", "openai.api.key");

    /** Chaves de uma entrada-mapa que carregam o texto da mensagem, em ordem de preferência. */
    static final List<String> MESSAGE_KEYS = List.of("input", "message", "text", "prompt");

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** Aliases amigáveis do designer → nomes que os adapters leem. */
    private static final Map<String, String> ALIASES = Map.of(
            "apiKey", API_KEY_PROPERTY,
            "model", MODEL_PROPERTY,
            "modelName", MODEL_PROPERTY);

    private final String provider;
    private final String adapterType;
    private final Map<String, Object> nodeConfig;
    private final TenantKeyResolver keyResolver;
    private final BaseUrlPolicy baseUrlPolicy;
    private final ProviderPreferences preferences;
    private final boolean preferencesRequired;
    private final List<String> openRouterUrls;
    private final TenantModelPolicy modelPolicy;
    private final Map<String, LangChainAdapter> byTenant = new ConcurrentHashMap<>();

    public LangChainAdapterComponent(String provider, String adapterType,
                                     Map<String, Object> nodeConfig,
                                     TenantKeyResolver keyResolver) {
        this(provider, adapterType, nodeConfig, keyResolver, BaseUrlPolicy.officialOnly());
    }

    /**
     * @param baseUrlPolicy destinos que podem receber a chave injetada pelo resolver; nulo é
     *                      "só os oficiais" — o default seguro
     */
    public LangChainAdapterComponent(String provider, String adapterType,
                                     Map<String, Object> nodeConfig,
                                     TenantKeyResolver keyResolver,
                                     BaseUrlPolicy baseUrlPolicy) {
        this(provider, adapterType, nodeConfig, keyResolver, baseUrlPolicy, ProviderPreferences.NONE, false);
    }

    /**
     * @param preferences         {@code archflow.llm.provider-preferences} do deployment
     * @param preferencesRequired {@code archflow.llm.provider-preferences.required}
     */
    public LangChainAdapterComponent(String provider, String adapterType,
                                     Map<String, Object> nodeConfig,
                                     TenantKeyResolver keyResolver,
                                     BaseUrlPolicy baseUrlPolicy,
                                     ProviderPreferences preferences,
                                     boolean preferencesRequired) {
        this(provider, adapterType, nodeConfig, keyResolver, baseUrlPolicy, preferences, preferencesRequired,
                List.of());
    }

    /**
     * @param openRouterUrls endereços que o deployment declarou serem o OpenRouter fora do host
     *                       {@code openrouter.ai} — o {@code archflow.llm.base-url} quando
     *                       {@code archflow.llm.provider=openrouter}. Recebem a preferência como o
     *                       {@code openrouter.ai}, para o nó não ficar sem a garantia que o agente do
     *                       mesmo deployment tem ({@link OpenRouterChatAdapter#isOpenRouterDestination})
     */
    public LangChainAdapterComponent(String provider, String adapterType,
                                     Map<String, Object> nodeConfig,
                                     TenantKeyResolver keyResolver,
                                     BaseUrlPolicy baseUrlPolicy,
                                     ProviderPreferences preferences,
                                     boolean preferencesRequired,
                                     List<String> openRouterUrls) {
        this(provider, adapterType, nodeConfig, keyResolver, baseUrlPolicy, preferences, preferencesRequired,
                openRouterUrls, TenantModelPolicy.UNRESTRICTED);
    }

    /**
     * @param modelPolicy modelos que cada tenant pode usar; nulo é "sem restrição" (testes, standalone)
     */
    public LangChainAdapterComponent(String provider, String adapterType,
                                     Map<String, Object> nodeConfig,
                                     TenantKeyResolver keyResolver,
                                     BaseUrlPolicy baseUrlPolicy,
                                     ProviderPreferences preferences,
                                     boolean preferencesRequired,
                                     List<String> openRouterUrls,
                                     TenantModelPolicy modelPolicy) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.adapterType = Objects.requireNonNull(adapterType, "adapterType");
        this.nodeConfig = nodeConfig == null ? Map.of() : Map.copyOf(nodeConfig);
        this.keyResolver = keyResolver != null ? keyResolver : TenantKeyResolver.NOOP;
        this.baseUrlPolicy = baseUrlPolicy != null ? baseUrlPolicy : BaseUrlPolicy.officialOnly();
        this.preferences = preferences != null ? preferences : ProviderPreferences.NONE;
        this.preferencesRequired = preferencesRequired;
        this.openRouterUrls = openRouterUrls == null ? List.of()
                : openRouterUrls.stream().filter(LangChainAdapterComponent::temTexto).toList();
        this.modelPolicy = modelPolicy != null ? modelPolicy : TenantModelPolicy.UNRESTRICTED;
    }

    /** Id estável deste nó adapter, no formato {@code provider:tipo}. */
    public String componentId() {
        return provider + ":" + adapterType;
    }

    @Override
    public void initialize(Map<String, Object> config) {
        // A configuração chega pelo construtor (é do nó, não do catálogo).
    }

    @Override
    public ComponentMetadata getMetadata() {
        return new ComponentMetadata(
                componentId(),
                provider + " (" + adapterType + ")",
                "Adapter LangChain4j " + provider + " para " + adapterType,
                componentTypeFor(adapterType),
                "1.0.0",
                Set.of(adapterType),
                List.of(),
                Map.of(),
                Set.of("langchain4j", provider, adapterType));
    }

    @Override
    public Object execute(String operation, Object input, ExecutionContext context) throws Exception {
        String tenantId = context != null && context.getTenantId() != null
                ? context.getTenantId()
                : "__global__";
        if ("chat".equals(adapterType)) {
            modelPolicy.check(tenantId, provider, modelsUsed());
            return adapterFor(tenantId).execute(chatOperation(operation), messageText(input),
                    ChatNodeContext.of(context));
        }
        return adapterFor(tenantId).execute(operation, input, context);
    }

    /**
     * A operação que um adapter de chat entende. {@code execute} é o default genérico do motor
     * ({@code DefaultFlowStepFactory.operationOf}) e o que todo nó feito no designer leva — não é
     * uma escolha de quem montou o fluxo. Vira {@code generate}: o prompt de sistema do nó e a
     * mensagem, nada mais.
     *
     * <p>Não vira {@code chat} porque a memória da execução é <b>uma só</b> para o fluxo inteiro (a
     * janela de 20 mensagens do {@code ExecutionContext}). Com {@code chat} por padrão, num
     * {@code entrada → resumir (A) → traduzir (B)} o B mandava a conversa do A e recebia a resposta
     * do A duas vezes (como turno do assistente e como a sua entrada); se B fosse de outro provider,
     * a pergunta original — talvez sensível — ia junto para ele; e dois nós em ramos paralelos
     * escreviam na mesma janela. Compartilhar a conversa é escolha explícita de quem monta o fluxo:
     * {@code operation: chat} no nó.
     */
    static String chatOperation(String operation) {
        if (operation != null && !operation.isBlank() && !"execute".equals(operation)) {
            return operation;
        }
        return "generate";
    }

    /**
     * O texto da mensagem do usuário a partir da entrada do passo. Os adapters exigem uma
     * {@code String}, e a entrada de um fluxo executado com variáveis JSON — ou a saída-objeto do
     * nó anterior — chega como mapa ("Input must be a string").
     *
     * <ul>
     *   <li>texto: como veio;</li>
     *   <li>mapa: o primeiro texto não vazio entre {@code input}, {@code message}, {@code text} e
     *       {@code prompt} (as chaves com que se diz "a mensagem"); sem nenhum, o JSON do mapa —
     *       o modelo vê os dados em vez de um {@code toString} de Java;</li>
     *   <li>lista e demais objetos: o JSON; nulo continua nulo (o adapter diz o que falta).</li>
     * </ul>
     */
    static Object messageText(Object input) {
        if (input == null || input instanceof String) {
            return input;
        }
        if (input instanceof Map<?, ?> map) {
            for (String key : MESSAGE_KEYS) {
                if (map.get(key) instanceof String text && !text.isBlank()) {
                    return text;
                }
            }
        }
        if (input instanceof Number || input instanceof Boolean || input instanceof CharSequence) {
            return String.valueOf(input);
        }
        try {
            return JSON.writeValueAsString(input);
        } catch (com.fasterxml.jackson.core.JsonProcessingException | RuntimeException e) {
            return String.valueOf(input);
        }
    }

    @Override
    public void shutdown() {
        byTenant.values().forEach(adapter -> {
            try {
                adapter.shutdown();
            } catch (RuntimeException e) {
                log.debug("Falha ao encerrar adapter {}: {}", componentId(), e.getMessage());
            }
        });
        byTenant.clear();
    }

    private LangChainAdapter adapterFor(String tenantId) {
        return byTenant.computeIfAbsent(tenantId, this::createAdapter);
    }

    private LangChainAdapter createAdapter(String tenantId) {
        Map<String, Object> properties = effectiveProperties(tenantId);
        log.debug("Criando adapter {} para tenant {}", componentId(), tenantId);
        return LangChainRegistry.createAdapter(provider, adapterType, properties);
    }

    /**
     * Config do nó normalizada, com a chave do tenant injetada.
     *
     * <p>O resolver tem precedência sobre a chave inline: um segredo esquecido
     * no JSON do workflow não pode sobrepor o que a governança do tenant diz.
     *
     * <p>Tudo o que pode falhar aqui falha <b>antes</b> de o adapter existir —
     * nenhuma requisição sai com destino recusado ou sem chave.
     */
    // Pacote, não privado: o teste confere o que o adapter receberia sem precisar da rede.
    Map<String, Object> effectiveProperties(String tenantId) {
        Map<String, Object> properties = new LinkedHashMap<>();
        nodeConfig.forEach((key, value) -> properties.put(ALIASES.getOrDefault(key, key), value));
        String keyProperty = keyProperty();
        if (!keyProperty.equals(API_KEY_PROPERTY) && !temTexto(properties.get(keyProperty))
                && temTexto(properties.get(API_KEY_PROPERTY))) {
            // O alias do designer (apiKey → api.key) tem de chegar onde este adapter lê.
            properties.put(keyProperty, properties.get(API_KEY_PROPERTY));
        }
        String governed = keyResolver.resolveApiKey(tenantId, provider)
                .filter(key -> !key.isBlank())
                .orElse(null);
        if (governed != null) {
            for (String campo : URL_PROPERTIES) {
                if (properties.get(campo) instanceof String url && !url.isBlank()) {
                    baseUrlPolicy.check(provider, url, campo);
                }
            }
            properties.put(keyProperty, governed);
            if (!keyProperty.equals(API_KEY_PROPERTY)) {
                // Uma chave inline esquecida no nó não fica ao lado da governada.
                properties.remove(API_KEY_PROPERTY);
            }
        } else if (temTexto(properties.get(keyProperty))) {
            // Uma vez por (nó, tenant): o adapter fica em cache, então isto não repete a cada chamada.
            log.warn("Nó {} usa uma apiKey escrita no JSON do workflow (tenant {}). Configure "
                    + "archflow.llm.keys.{} ou archflow.llm.tenant-keys.<tenant>.{} e remova a chave do nó.",
                    componentId(), tenantId, provider, provider);
        } else if (keyIsTheLlmProviders() && LLMProvider.fromId(provider).map(LLMProvider::requiresApiKey)
                .orElse(false)) {
            throw new IllegalStateException(missingKeyMessage(tenantId));
        }
        exigirPreferenciaDeProvedor(properties);
        exigirNumeros(properties);
        return properties;
    }

    /**
     * Os modelos que este nó de chat chama: o principal ({@code model}/{@code modelName}/
     * {@code model.name}, já com o herdado do fluxo/plataforma) e, quando o nó configura um destino de
     * fallback, o {@code fallback.model.name} — sem esse, um tenant restrito trocaria de modelo pelo
     * fallback. Um {@code null} na lista é "o nó não diz o modelo" (iria o default do adapter).
     */
    List<String> modelsUsed() {
        // A mesma normalização de effectiveProperties (último alias vence), para conferir o id que o
        // adapter vai de fato receber.
        Object raw = null;
        for (Map.Entry<String, Object> e : nodeConfig.entrySet()) {
            if (MODEL_PROPERTY.equals(ALIASES.getOrDefault(e.getKey(), e.getKey()))) {
                raw = e.getValue();
            }
        }
        String model = temTexto(raw) ? ((String) raw).trim() : null;
        List<String> models = new java.util.ArrayList<>();
        models.add(model);
        if (temTexto(nodeConfig.get("fallback.base.url"))) {
            Object fallback = nodeConfig.get("fallback.model.name");
            // Sem o nome, o adapter do OpenRouter usa "llama3.1": conta como "não disse".
            models.add(temTexto(fallback) ? ((String) fallback).trim() : null);
        }
        return models;
    }

    /** Campos que os adapters de chat leem com {@code (Number)}. */
    static final List<String> NUMERIC_CHAT_PROPERTIES = List.of("temperature", "maxTokens");

    /**
     * Um {@code temperature}/{@code maxTokens} que não é número (o {@link ChatNodeDefaults} já tirou o
     * texto em branco e converteu o numérico) virava {@code ClassCastException} dentro do adapter e
     * chegava ao usuário como "Error executing operation: chat". Aqui a falha diz o campo e o valor.
     */
    private void exigirNumeros(Map<String, Object> properties) {
        if (!"chat".equals(adapterType)) {
            return;
        }
        for (String campo : NUMERIC_CHAT_PROPERTIES) {
            Object valor = properties.get(campo);
            if (valor != null && !(valor instanceof Number)) {
                throw new IllegalArgumentException("Nó " + componentId() + ": '" + campo
                        + "' deve ser um número, veio \"" + valor + "\".");
            }
        }
    }

    /** Onde o adapter deste nó lê o segredo. */
    private String keyProperty() {
        return KEY_PROPERTY_BY_ADAPTER.getOrDefault(componentId(), API_KEY_PROPERTY);
    }

    /**
     * A falta de chave só é erro de configuração quando a chave deste adapter é a do provider de
     * LLM: os de chat, e os que declaram onde a leem. Um vector store ou memória com o mesmo nome
     * de provider (ex.: {@code redis}) não usa chave de LLM nenhuma.
     */
    private boolean keyIsTheLlmProviders() {
        return "chat".equals(adapterType) || KEY_PROPERTY_BY_ADAPTER.containsKey(componentId());
    }

    /**
     * Preferência de provedor do OpenRouter ({@code archflow.llm.provider-preferences}) é do
     * deployment: nenhum nó a desliga. Vai ao adapter de chat do OpenRouter nas propriedades
     * {@link OpenRouterChatAdapter#PREFERENCES_PROPERTY} / {@code .required}, e ele põe o objeto
     * {@code provider: {zdr, data_collection}} no corpo de cada destino do OpenRouter (principal,
     * por contexto e fallback) — antes o nó ia sem ele e o OpenRouter podia rotear para um provedor que
     * retém ou treina com os dados (RCHFLW-41).
     *
     * <p>As três chaves (preferência, {@code required} e os endereços tratados como OpenRouter) são
     * <b>sempre</b> removidas do que veio do nó e só então postas a partir do
     * deployment: a config do nó é copiada inteira para as propriedades, então um
     * {@code "provider.preferences": {}} ou {@code "provider.preferences.required": false} no JSON do
     * fluxo desligaria a garantia.
     *
     * <p>Com {@code required=true} e nenhuma preferência, falha aqui, antes de o adapter existir, quando o
     * destino principal é o OpenRouter ({@link OpenRouterChatAdapter#isOpenRouterDestination}: host
     * {@code openrouter.ai} ou um endereço do deployment declarado como OpenRouter); o adapter repete a
     * checagem para cada destino, inclusive o fallback.
     */
    private void exigirPreferenciaDeProvedor(Map<String, Object> properties) {
        properties.remove(OpenRouterChatAdapter.PREFERENCES_PROPERTY);
        properties.remove(OpenRouterChatAdapter.PREFERENCES_REQUIRED_PROPERTY);
        // Um nó que se declarasse "OpenRouter" mandaria o campo a um Ollama (400); é do deployment.
        properties.remove(OpenRouterChatAdapter.PREFERENCES_OPENROUTER_URLS_PROPERTY);
        if (!"openrouter".equals(provider) || !"chat".equals(adapterType)) {
            return;
        }
        properties.put(OpenRouterChatAdapter.PREFERENCES_PROPERTY, preferences);
        properties.put(OpenRouterChatAdapter.PREFERENCES_REQUIRED_PROPERTY, preferencesRequired);
        properties.put(OpenRouterChatAdapter.PREFERENCES_OPENROUTER_URLS_PROPERTY, openRouterUrls);
        if (!preferencesRequired || !preferences.isEmpty()) {
            return;
        }
        String destino = properties.get("base.url") instanceof String url && !url.isBlank()
                ? url.trim() : LLMProvider.OPENROUTER.getBaseUrl();
        if (OpenRouterChatAdapter.isOpenRouterDestination(destino, openRouterUrls)) {
            throw new IllegalStateException("[LLM-ZDR] archflow.llm.provider-preferences.required=true, mas "
                    + "nenhuma preferência de provedor está configurada para o OpenRouter "
                    + "(archflow.llm.provider-preferences.zdr / .data-collection). Nó " + componentId()
                    + " NÃO executado — a requisição iria sem a garantia.");
        }
    }

    private static boolean temTexto(Object valor) {
        return valor instanceof String s && !s.isBlank();
    }

    /** Sem chave: diga onde ela deveria estar, em vez do "api.key required" cru do adapter. */
    private String missingKeyMessage(String tenantId) {
        String tenant = TenantKeyResolver.isNoTenant(tenantId) ? "(plataforma)" : "'" + tenantId + "'";
        String env = "ARCHFLOW_LLM_KEYS_" + provider.toUpperCase(Locale.ROOT).replace('-', '_');
        return "[LLM-CHAVE] Sem chave de API para o provider '" + provider + "' no tenant " + tenant
                + ". Configure archflow.llm.tenant-keys.<tenant>." + provider + " (chave do tenant), "
                + "archflow.llm.keys." + provider + " (env " + env + ") ou archflow.llm.api-key quando '"
                + provider + "' é o archflow.llm.provider. A chave não vai no JSON do fluxo.";
    }

    private static ComponentType componentTypeFor(String adapterType) {
        return switch (adapterType) {
            case "chat" -> ComponentType.ASSISTANT;
            default -> ComponentType.TOOL;
        };
    }
}
