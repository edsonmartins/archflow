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
 *
 * <h2>Nós de dados: conexão do servidor, chave com o nome do adapter, operação padrão (RCHFLW-56)</h2>
 * Embedding, vector store, memória e RAG recebem a config já traduzida por {@link DesignerAdapterKeys}. Aqui:
 * credencial e destino de banco/redis/pinecone que vierem do JSON são descartados
 * ({@link AdapterConnections#SERVER_ONLY_PROPERTIES}) e a conexão escolhida por nome é lida do servidor; a chave do
 * tenant vai no nome que cada adapter lê ({@link #KEY_PROPERTY_BY_ADAPTER}) — no RAG, uma para o embedding e outra
 * para o chat que ele cria com o mesmo mapa, que também leva a preferência de provedor do OpenRouter e confere os
 * modelos do tenant; e o {@code execute} genérico do motor vira a operação do tipo ({@code embed}, {@code query},
 * {@code search}), com a entrada e a saída no formato que o fluxo carrega ({@link #dataInput}, {@link #dataOutput}).
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

    /**
     * Quantos e com que score mínimo a busca de um vector store devolve — o {@code topK}/{@code minScore}
     * do nó, traduzidos por {@link DesignerAdapterKeys}. Não são do adapter: a ponte monta com eles o
     * {@code EmbeddingSearchRequest} que o adapter recebe ({@link #searchRequest}).
     */
    static final String SEARCH_MAX_RESULTS = "search.maxResults";
    static final String SEARCH_MIN_SCORE = "search.minScore";

    /**
     * A operação que vale quando o nó não diz uma ({@code DefaultFlowStepFactory.operationOf} manda
     * {@code execute}), por <b>tipo de nó</b>. O designer não tem campo de operação nesses nós, e nenhum adapter
     * de dados aceita {@code execute} — sem isto, o nó de Embedding ou RAG que chegasse ao adapter falhava com
     * "Unsupported operation: execute" (RCHFLW-56).
     *
     * <p>É por tipo de nó, e não de adapter, porque {@code vector-search} e {@code vector-store} vão ao mesmo
     * adapter e prometem coisas opostas: buscar e <i>gravar</i>. Nenhum adapter de vector store grava (só
     * {@code search}, {@code remove} e {@code removeAll}); dar {@code search} ao {@code vector-store} fazia o
     * nó "Armazena embeddings" buscar e voltar com sucesso sem ter indexado nada. Ele e a memória (guardar?
     * ler? limpar?) não têm padrão: sem {@code operation} no nó, recusam dizendo as opções — a memória tem o campo
     * Operação no painel (RCHFLW-115).</p>
     */
    static final Map<String, String> DEFAULT_OPERATION_BY_NODE_TYPE = Map.of(
            "embedding", "embed",
            "rag", "query",
            "vector-search", "search");

    /** As operações que cada adapter de dados sem padrão aceita — o que a recusa sugere. */
    static final Map<String, String> OPERATIONS_BY_ADAPTER_TYPE = Map.of(
            "memory", "add, get ou clear",
            "vectorstore", "search, remove ou removeAll");

    /**
     * As operações dos adapters de memória ({@code RedisMemoryAdapter}, {@code JdbcMemoryAdapter}): são as opções
     * do campo Operação do painel (RCHFLW-115). O {@code DesignerAdapterKeysConformanceTest} confere que o painel
     * oferece exatamente estas — uma opção a mais viraria "Unsupported operation" só ao executar.
     */
    public static final List<String> MEMORY_OPERATIONS = List.of("add", "get", "clear");

    /**
     * Onde a ponte lê o papel da mensagem que o {@code add} guarda (o campo {@code memoryRole} do painel, traduzido
     * por {@link DesignerAdapterKeys}). Os adapters de memória só aceitam um {@code ChatMessage}, e a entrada de um
     * passo é texto ou JSON: sem dizer de quem é a mensagem, não há {@code ChatMessage} a montar.
     */
    static final String MEMORY_ROLE = "memory.role";

    /**
     * Os papéis de uma mensagem feita só de texto: os que os adapters de memória também montam a partir do texto
     * no formato antigo ({@code UserMessage}, {@code AiMessage}, {@code SystemMessage}). Resultado de ferramenta
     * fica de fora — precisa do id e do nome da chamada, que a entrada de um passo não tem.
     */
    public static final List<String> MEMORY_ROLES = List.of("user", "ai", "system");

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
    private final AdapterConnections connections;
    private final String nodeType;
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
        this(provider, adapterType, nodeConfig, keyResolver, baseUrlPolicy, preferences, preferencesRequired,
                openRouterUrls, modelPolicy, AdapterConnections.NONE);
    }

    /**
     * @param connections conexões do servidor por nome (banco do pgvector, redis, pinecone); nulo é
     *                    "nenhuma" — o nó que precisa de uma falha dizendo a propriedade a definir
     */
    public LangChainAdapterComponent(String provider, String adapterType,
                                     Map<String, Object> nodeConfig,
                                     TenantKeyResolver keyResolver,
                                     BaseUrlPolicy baseUrlPolicy,
                                     ProviderPreferences preferences,
                                     boolean preferencesRequired,
                                     List<String> openRouterUrls,
                                     TenantModelPolicy modelPolicy,
                                     AdapterConnections connections) {
        this(provider, adapterType, nodeConfig, keyResolver, baseUrlPolicy, preferences, preferencesRequired,
                openRouterUrls, modelPolicy, connections, null);
    }

    /**
     * @param nodeType o {@code type} do nó do designer ({@code vector-search}, {@code embedding}...), que decide
     *                 a operação padrão ({@link #DEFAULT_OPERATION_BY_NODE_TYPE}); nulo é "sem padrão" — o nó
     *                 precisa dizer a operação
     */
    public LangChainAdapterComponent(String provider, String adapterType,
                                     Map<String, Object> nodeConfig,
                                     TenantKeyResolver keyResolver,
                                     BaseUrlPolicy baseUrlPolicy,
                                     ProviderPreferences preferences,
                                     boolean preferencesRequired,
                                     List<String> openRouterUrls,
                                     TenantModelPolicy modelPolicy,
                                     AdapterConnections connections,
                                     String nodeType) {
        this.nodeType = nodeType == null || nodeType.isBlank() ? null : nodeType.trim().toLowerCase(Locale.ROOT);
        this.connections = connections != null ? connections : AdapterConnections.NONE;
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

    /**
     * A config do nó sem os campos de segredo que um nó pode carregar ({@code apiKey}, {@code api.key}, o
     * nome próprio de cada adapter, {@link #KEY_PROPERTY_BY_ADAPTER}, e {@code fallback.api.key}).
     *
     * <p>O servidor ainda aceita a chave inline para desenvolvimento (com aviso, e o resolver vence). O runner
     * standalone e o SDK embarcado não (RCHFLW-37): lá a chave vem só do ambiente, e um arquivo de fluxo que
     * circula entre máquinas não pode decidir com que credencial o processo fala.</p>
     */
    public static Map<String, Object> withoutInlineKeys(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return config == null ? Map.of() : config;
        }
        Map<String, Object> semChave = new LinkedHashMap<>(config);
        semChave.keySet().removeAll(INLINE_KEY_FIELDS);
        return semChave.size() == config.size() ? config : Map.copyOf(semChave);
    }

    /**
     * A chave que o {@code OpenRouterChatAdapter} manda ao {@code fallback.base.url}. Também é segredo do
     * arquivo: sem ela o adapter usa o padrão dele (um Ollama local não pede chave) — o ambiente não tem chave
     * para esse destino, e mandar a do provider principal a outro endereço seria vazá-la.
     */
    static final String FALLBACK_API_KEY_PROPERTY = "fallback.api.key";

    /**
     * Os nomes com que um nó pode trazer a chave: o alias do designer, o do adapter, os por adapter e a chave
     * do modelo reserva do OpenRouter ({@link #FALLBACK_API_KEY_PROPERTY}).
     */
    private static final Set<String> INLINE_KEY_FIELDS = java.util.stream.Stream.concat(
                    java.util.stream.Stream.of("apiKey", API_KEY_PROPERTY, FALLBACK_API_KEY_PROPERTY),
                    KEY_PROPERTY_BY_ADAPTER.values().stream())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

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
        if ("chain".equals(adapterType) && temTexto(nodeConfig.get(RAG_LLM_PROVIDER))) {
            // O RAG chama um modelo de chat: o tenant restrito a uma lista também é restrito aqui.
            modelPolicy.check(tenantId, ((String) nodeConfig.get(RAG_LLM_PROVIDER)).trim(), modelsUsed());
        }
        String op = dataOperation(operation);
        Object output = adapterFor(tenantId).execute(op, dataInput(op, input), context);
        // Operações que só gravam (add/clear da memória, remove do vector store) devolvem null, e o passo guarda a
        // saída nas variáveis da execução, que não aceitam null — o nó falhava com NullPointerException depois de
        // ter gravado. Sem saída própria, o nó passa adiante o que recebeu: o passo seguinte vê a mesma entrada.
        return output == null ? input : dataOutput(output);
    }

    /** Onde o RAG lê o provider de cada papel (as chaves do {@code RagChainAdapter}). */
    static final String RAG_LLM_PROVIDER = "languagemodel.provider";
    static final String RAG_EMBEDDING_PROVIDER = "embedding.provider";
    static final String RAG_VECTORSTORE_PROVIDER = "vectorstore.provider";

    /**
     * A operação de um adapter de dados: a do nó, ou a padrão do tipo de nó quando o nó não diz
     * ({@code execute} é o default genérico do motor, não uma escolha) — ver
     * {@link #DEFAULT_OPERATION_BY_NODE_TYPE}. Sem padrão, recusa: mandar {@code execute} ao adapter só
     * trocaria a mensagem por "Unsupported operation: execute".
     */
    String dataOperation(String operation) {
        if (operation != null && !operation.isBlank() && !"execute".equals(operation)) {
            return operation;
        }
        String padrao = nodeType == null ? null : DEFAULT_OPERATION_BY_NODE_TYPE.get(nodeType);
        if (padrao != null) {
            return padrao;
        }
        String opcoes = OPERATIONS_BY_ADAPTER_TYPE.get(adapterType);
        String gravacao = "vectorstore".equals(adapterType)
                ? " Nenhum adapter de vector store grava vetores; para buscar, use o nó Vector search." : "";
        // A memória tem o campo Operação no painel (RCHFLW-115); o vector store ainda não.
        String onde = "memory".equals(adapterType)
                ? ": escolha a Operação no painel do nó." : ", e o painel ainda não tem esse campo.";
        throw new IllegalArgumentException("Nó " + componentId()
                + (nodeType == null ? "" : " (" + nodeType + ")") + ": diga a operação em 'operation'"
                + (opcoes == null ? "" : " (" + opcoes + ")")
                + ". Este tipo de nó não tem uma operação padrão" + onde + gravacao);
    }

    /**
     * A entrada no formato que a operação do adapter exige: texto para {@code embed} e {@code query}
     * (o mapa da entrada do fluxo vira a mensagem, como no chat) e um {@code EmbeddingSearchRequest}
     * para {@code search}. As demais operações recebem a entrada como veio.
     */
    Object dataInput(String operation, Object input) {
        return switch (operation) {
            case "embed", "query" -> messageText(input);
            case "search" -> "vectorstore".equals(adapterType) ? searchRequest(input) : input;
            case "add" -> "memory".equals(adapterType) ? memoryMessage(input) : input;
            default -> input;
        };
    }

    /**
     * A mensagem que o {@code add} da memória guarda. Os adapters só aceitam um {@code ChatMessage} ("Input must be
     * a ChatMessage"), e a entrada que chega de um passo é texto ou a saída-objeto do anterior: o texto é o mesmo
     * que um nó de chat leria ({@link #messageText}) e o papel é o que o nó escolheu ({@link #MEMORY_ROLE}). Sem
     * papel, recusa — guardar a resposta do modelo como se fosse do usuário mudaria a conversa que o {@code get}
     * devolve depois.
     */
    dev.langchain4j.data.message.ChatMessage memoryMessage(Object input) {
        if (input instanceof dev.langchain4j.data.message.ChatMessage message) {
            return message;
        }
        String role = texto(nodeConfig.get(MEMORY_ROLE));
        if (role == null) {
            throw new IllegalArgumentException("Nó " + componentId() + nodeTypeSuffix() + ": a operação add guarda a "
                    + "entrada como uma mensagem, e o nó não diz de quem ela é. Escolha o Papel da mensagem no "
                    + "painel (" + String.join(", ", MEMORY_ROLES) + ").");
        }
        if (!(messageText(input) instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Nó " + componentId() + nodeTypeSuffix()
                    + ": a operação add não recebeu nada para guardar — a entrada do passo está vazia.");
        }
        return switch (role.toLowerCase(Locale.ROOT)) {
            case "user" -> dev.langchain4j.data.message.UserMessage.from(text);
            case "ai" -> dev.langchain4j.data.message.AiMessage.from(text);
            case "system" -> dev.langchain4j.data.message.SystemMessage.from(text);
            default -> throw new IllegalArgumentException("Nó " + componentId() + nodeTypeSuffix()
                    + ": 'memoryRole' (" + MEMORY_ROLE + ") deve ser " + String.join(", ", MEMORY_ROLES)
                    + ", veio \"" + role + "\"");
        };
    }

    private String nodeTypeSuffix() {
        return nodeType == null ? "" : " (" + nodeType + ")";
    }

    /**
     * O pedido de busca a partir da saída de um nó Embedding (o vetor) e do {@code topK}/{@code minScore}
     * do nó. Texto não vira vetor aqui: escolher um modelo de embedding seria inventar um que o nó não
     * pediu — a falha diz o que pôr antes, ou que a Base de conhecimento recebe texto (RCHFLW-55).
     */
    dev.langchain4j.store.embedding.EmbeddingSearchRequest searchRequest(Object input) {
        if (input instanceof dev.langchain4j.store.embedding.EmbeddingSearchRequest request) {
            return request;
        }
        dev.langchain4j.data.embedding.Embedding embedding = embeddingOf(input);
        if (embedding == null) {
            throw new IllegalArgumentException("Nó " + componentId() + ": a busca vetorial recebe um vetor (a "
                    + "saída de um nó Embedding) e chegou " + (input == null ? "nada" : "texto/objeto sem 'vector'")
                    + ". Ponha um nó Embedding antes dele"
                    // Só vector-search e rag têm o seletor de base; sugerir a base a um vector-store seria um
                    // conselho que o painel não deixa seguir.
                    + (nodeType != null
                            && br.com.archflow.api.flow.KnowledgeNodes.NODE_TYPES.contains(nodeType)
                            ? ", ou escolha uma Base de conhecimento no nó — aí a busca recebe o texto." : "."));
        }
        var builder = dev.langchain4j.store.embedding.EmbeddingSearchRequest.builder().queryEmbedding(embedding);
        if (nodeConfig.get(SEARCH_MAX_RESULTS) instanceof Integer max) {
            builder.maxResults(max);
        }
        if (nodeConfig.get(SEARCH_MIN_SCORE) instanceof Double min) {
            builder.minScore(min);
        }
        return builder.build();
    }

    /** O vetor de uma saída de nó Embedding: o objeto do LangChain4j, a {@code Response} dele ou o mapa da ponte. */
    private static dev.langchain4j.data.embedding.Embedding embeddingOf(Object value) {
        if (value instanceof dev.langchain4j.data.embedding.Embedding embedding) {
            return embedding;
        }
        if (value instanceof dev.langchain4j.model.output.Response<?> response) {
            return embeddingOf(response.content());
        }
        if (value instanceof Map<?, ?> map && map.containsKey("vector")) {
            return embeddingOf(map.get("vector"));
        }
        if (value instanceof float[] vector) {
            return dev.langchain4j.data.embedding.Embedding.from(vector);
        }
        if (value instanceof List<?> list && !list.isEmpty() && list.stream().allMatch(Number.class::isInstance)) {
            float[] vector = new float[list.size()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = ((Number) list.get(i)).floatValue();
            }
            return dev.langchain4j.data.embedding.Embedding.from(vector);
        }
        return null;
    }

    /**
     * A saída do adapter num formato que o fluxo guarda e mostra (JSON): os tipos do LangChain4j não
     * têm getters e não serializam. Vetor vira {@code {vector, dimension}} — o que o nó de busca aceita
     * de volta —, resultado de busca vira a lista de trechos.
     */
    static Object dataOutput(Object output) {
        if (output instanceof dev.langchain4j.model.output.Response<?> response) {
            return dataOutput(response.content());
        }
        if (output instanceof dev.langchain4j.data.embedding.Embedding embedding) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("vector", embedding.vectorAsList());
            out.put("dimension", embedding.dimension());
            return out;
        }
        if (output instanceof List<?> list && !list.isEmpty()
                && list.stream().allMatch(dev.langchain4j.data.embedding.Embedding.class::isInstance)) {
            return list.stream().map(LangChainAdapterComponent::dataOutput).toList();
        }
        if (output instanceof List<?> list && !list.isEmpty()
                && list.stream().allMatch(dev.langchain4j.data.message.ChatMessage.class::isInstance)) {
            // O histórico do get da memória: cada mensagem no formato canônico que os adapters gravam
            // (ChatMessageCodec), que o fluxo guarda e a tela mostra — o ChatMessage em si não serializa.
            return list.stream()
                    .map(m -> messageMap((dev.langchain4j.data.message.ChatMessage) m))
                    .toList();
        }
        if (output instanceof dev.langchain4j.store.embedding.EmbeddingSearchResult<?> result) {
            return result.matches().stream().map(match -> {
                Map<String, Object> hit = new LinkedHashMap<>();
                hit.put("id", match.embeddingId());
                hit.put("score", match.score());
                if (match.embedded() instanceof dev.langchain4j.data.segment.TextSegment segment) {
                    hit.put("text", segment.text());
                    hit.put("metadata", segment.metadata().toMap());
                }
                return hit;
            }).toList();
        }
        return output;
    }

    private static Object messageMap(dev.langchain4j.data.message.ChatMessage message) {
        try {
            return JSON.readValue(br.com.archflow.langchain4j.core.memory.ChatMessageCodec.toJson(message), Map.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("mensagem da memória sem JSON canônico: " + e.getMessage(), e);
        }
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
    public static Object messageText(Object input) {
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
        // Credencial e destino de banco/redis/pinecone não vêm do JSON do fluxo (RCHFLW-56): o que vier
        // é descartado e a conexão do servidor, escolhida por nome, preenche.
        AdapterConnections.SERVER_ONLY_PROPERTIES.forEach(properties::remove);
        resolverConexao(properties);
        if ("chain".equals(adapterType)) {
            // O RAG cria o embedding e o chat com este mesmo mapa: cada um recebe a chave do seu provider,
            // com o nome que o adapter dele lê (o embedding da OpenAI lê openai.api.key, o chat api.key).
            String embedding = texto(properties.get(RAG_EMBEDDING_PROVIDER));
            String embeddingKey = embedding == null ? null : KEY_PROPERTY_BY_ADAPTER.get(embedding + ":embedding");
            if (embeddingKey != null) {
                injetarChave(properties, tenantId, embedding, embeddingKey, true);
            }
            String llm = texto(properties.get(RAG_LLM_PROVIDER));
            if (llm != null) {
                injetarChave(properties, tenantId, llm, API_KEY_PROPERTY, true);
            }
        } else {
            injetarChave(properties, tenantId, provider, keyProperty(), false);
        }
        exigirPreferenciaDeProvedor(properties);
        exigirNumeros(properties);
        String numero = DesignerAdapterKeys.numericProblem(properties);
        if (numero != null) {
            throw new IllegalArgumentException("Nó " + componentId() + ": " + numero + ".");
        }
        return properties;
    }

    /**
     * Põe a chave governada do {@code keyProvider} em {@code keyProperty}.
     *
     * <p>O resolver tem precedência sobre a chave inline; com a chave dele, os destinos do nó passam pela
     * {@link BaseUrlPolicy}. Sem chave nenhuma, o provider de LLM que exige uma falha dizendo onde
     * configurá-la.
     *
     * @param papelNoRag {@code true} quando a chave é de um dos adapters que o RAG cria: aí o
     *                   {@code api.key} inline é do chat e não é copiado nem removido por causa do
     *                   embedding, e só a chave do chat confere os destinos (o embedding não lê
     *                   {@code base.url})
     */
    private void injetarChave(Map<String, Object> properties, String tenantId, String keyProvider,
                              String keyProperty, boolean papelNoRag) {
        boolean outroNome = !keyProperty.equals(API_KEY_PROPERTY);
        if (!papelNoRag && outroNome && !temTexto(properties.get(keyProperty))
                && temTexto(properties.get(API_KEY_PROPERTY))) {
            // O alias do designer (apiKey → api.key) tem de chegar onde este adapter lê.
            properties.put(keyProperty, properties.get(API_KEY_PROPERTY));
        }
        String governed = keyResolver.resolveApiKey(tenantId, keyProvider)
                .filter(key -> !key.isBlank())
                .orElse(null);
        if (governed != null) {
            if (!outroNome || !papelNoRag) {
                for (String campo : URL_PROPERTIES) {
                    if (properties.get(campo) instanceof String url && !url.isBlank()) {
                        baseUrlPolicy.check(keyProvider, url, campo);
                    }
                }
            }
            properties.put(keyProperty, governed);
            if (outroNome && !papelNoRag) {
                // Uma chave inline esquecida no nó não fica ao lado da governada.
                properties.remove(API_KEY_PROPERTY);
            }
        } else if (temTexto(properties.get(keyProperty))) {
            // Uma vez por (nó, tenant): o adapter fica em cache, então isto não repete a cada chamada.
            log.warn("Nó {} usa uma apiKey escrita no JSON do workflow (tenant {}). Configure "
                    + "archflow.llm.keys.{} ou archflow.llm.tenant-keys.<tenant>.{} e remova a chave do nó.",
                    componentId(), tenantId, keyProvider, keyProvider);
        } else if ((papelNoRag || keyIsTheLlmProviders())
                && LLMProvider.fromId(keyProvider).map(LLMProvider::requiresApiKey).orElse(false)) {
            throw new IllegalStateException(missingKeyMessage(tenantId, keyProvider));
        }
    }

    /**
     * Troca o nome da conexão escolhida no nó ({@code <provider>.connection}, ver
     * {@link DesignerAdapterKeys}) pelas propriedades que a conexão do servidor declara. Vale para o
     * vector store e a memória do próprio nó e para o vector store de dentro do RAG.
     */
    private void resolverConexao(Map<String, Object> properties) {
        String store = switch (adapterType) {
            case "vectorstore", "memory" -> provider;
            case "chain" -> texto(properties.get(RAG_VECTORSTORE_PROVIDER));
            default -> null;
        };
        if (store == null) {
            return;
        }
        Object name = properties.remove(store + DesignerAdapterKeys.CONNECTION_SUFFIX);
        if ("memory".equals(adapterType) && "jdbc".equals(store)) {
            // O adapter JDBC espera um objeto DataSource, que não vem de JSON nem de uma string de conexão.
            throw new IllegalStateException("[ADAPTER-CONEXAO] Nó " + componentId() + ": a memória JDBC precisa "
                    + "de um DataSource do servidor, e o nó de fluxo ainda não recebe um. Escolha o backend redis "
                    + "(conexão em " + AdapterConnections.PREFIX + "redis.<nome>.host).");
        }
        if (AdapterConnections.needsConnection(store)) {
            properties.putAll(connections.resolve(componentId(), store, texto(name),
                    DesignerAdapterKeys.CONNECTION_FIELD_BY_PROVIDER.get(store)));
        }
    }

    private static String texto(Object valor) {
        return valor instanceof String s && !s.isBlank() ? s.trim() : null;
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
        // O chat do nó — ou o que o RAG cria com este mesmo mapa: a garantia vale para os dois.
        String chatProvider = "chat".equals(adapterType) ? provider
                : "chain".equals(adapterType) ? texto(properties.get(RAG_LLM_PROVIDER)) : null;
        if (!"openrouter".equals(chatProvider)) {
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
    private String missingKeyMessage(String tenantId, String provider) {
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
