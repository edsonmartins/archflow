package br.com.archflow.api.flow.adapter;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Tradução das chaves que o painel de propriedades grava nos nós de dados (Embedding, Vector
 * search/store, Memory, RAG) para as chaves que cada adapter LangChain4j lê (RCHFLW-56).
 *
 * <p><b>Por que existe.</b> O painel gravava {@code vectorBackend}, {@code pgDimensions},
 * {@code embeddingProvider}, {@code memoryBackend}... e nenhuma dessas chaves era lida no Java: o nó
 * não tinha {@code provider} e caía em "component not found", ou chegava ao {@code RagChainAdapter}
 * sem {@code embedding.provider} ("Embedding provider is required"). Os adapters leem
 * {@code pgvector.dimension}, {@code embedding.provider}, {@code retriever.maxResults}. Esta tabela
 * é o único lugar que liga uma coisa à outra; o {@code DesignerAdapterKeysConformanceTest} lê o
 * painel e falha quando ele grava uma chave que não está aqui, e passa a config traduzida pelo
 * {@code validate} de cada adapter de verdade.</p>
 *
 * <p><b>O documento não muda.</b> A tradução acontece ao construir o passo; o JSON salvo continua
 * com as chaves do designer — os três leitores do documento (factory, {@code SerializableStep},
 * DSL) seguem vendo o mesmo. Uma chave nativa do adapter escrita à mão continua valendo; quando o
 * nó tem as duas, vale a do designer, que é a que o painel mostra.</p>
 *
 * <p>As conexões (banco, redis, pinecone) entram aqui só pelo <b>nome</b>: a chave interna
 * {@code <provider>.connection} é resolvida no servidor por {@link AdapterConnections}.</p>
 */
public final class DesignerAdapterKeys {

    /** Como converter o valor do designer para o tipo que o adapter lê ({@code (Integer)}, {@code (Double)}). */
    enum Kind { TEXT, INTEGER, DECIMAL }

    /**
     * Uma chave do designer → a do adapter.
     *
     * @param provider {@code null} vale para qualquer provider do tipo
     */
    record Rule(String adapterType, String provider, String designerKey, String adapterKey, Kind kind) {
    }

    /** Sufixo da chave interna que carrega o nome da conexão do servidor ({@code pgvector.connection}). */
    static final String CONNECTION_SUFFIX = ".connection";

    /** O campo do designer que escolhe o provider, por tipo de adapter. O RAG é sempre o adapter {@code rag}. */
    static final Map<String, String> PROVIDER_FIELD_BY_ADAPTER_TYPE = Map.of(
            "vectorstore", "vectorBackend",
            "embedding", "embeddingProvider",
            "memory", "memoryBackend");

    /** Campo do designer onde o nó escolhe a conexão do servidor, por provider — para as mensagens. */
    public static final Map<String, String> CONNECTION_FIELD_BY_PROVIDER = Map.of(
            "pgvector", "pgDatasource",
            "redis", "redisConnection",
            "pinecone", "pineconeConnection");

    static final List<Rule> RULES = List.of(
            // Vector store (vector-search / vector-store) — e o vector store dentro do RAG.
            new Rule("vectorstore", "pgvector", "pgDatasource", "pgvector" + CONNECTION_SUFFIX, Kind.TEXT),
            new Rule("vectorstore", "pgvector", "pgTableName", "pgvector.table", Kind.TEXT),
            new Rule("vectorstore", "pgvector", "pgDimensions", "pgvector.dimension", Kind.INTEGER),
            new Rule("vectorstore", "redis", "redisConnection", "redis" + CONNECTION_SUFFIX, Kind.TEXT),
            new Rule("vectorstore", "redis", "vectorRedisPrefix", "redis.prefix", Kind.TEXT),
            new Rule("vectorstore", "redis", "vectorDimensions", "redis.dimension", Kind.INTEGER),
            new Rule("vectorstore", "pinecone", "pineconeConnection", "pinecone" + CONNECTION_SUFFIX, Kind.TEXT),
            new Rule("vectorstore", "pinecone", "pineconeIndex", "pinecone.indexName", Kind.TEXT),
            new Rule("vectorstore", "pinecone", "vectorDimensions", "pinecone.dimension", Kind.INTEGER),
            // A busca do nó (o adapter recebe um EmbeddingSearchRequest montado pela ponte).
            new Rule("vectorstore", null, "topK", LangChainAdapterComponent.SEARCH_MAX_RESULTS, Kind.INTEGER),
            new Rule("vectorstore", null, "minScore", LangChainAdapterComponent.SEARCH_MIN_SCORE, Kind.DECIMAL),
            // Embedding — e o embedding dentro do RAG.
            new Rule("embedding", "openai", "embeddingModel", "openai.model", Kind.TEXT),
            new Rule("embedding", "local", "embeddingDimensions", "local.dimension", Kind.INTEGER),
            // Memória.
            new Rule("memory", "redis", "redisConnection", "redis" + CONNECTION_SUFFIX, Kind.TEXT),
            new Rule("memory", null, "memoryMaxMessages", "memory.maxMessages", Kind.INTEGER),
            // RAG: os três providers e o retriever.
            new Rule("chain", null, "embeddingProvider", "embedding.provider", Kind.TEXT),
            new Rule("chain", null, "vectorBackend", "vectorstore.provider", Kind.TEXT),
            new Rule("chain", null, "llmProvider", "languagemodel.provider", Kind.TEXT),
            new Rule("chain", null, "retrieverMaxResults", "retriever.maxResults", Kind.INTEGER),
            new Rule("chain", null, "retrieverMinScore", "retriever.minScore", Kind.DECIMAL));

    /**
     * Chaves que o designer já gravou e que levavam credencial ou destino para dentro do fluxo. Não são
     * traduzidas: são descartadas, e a conexão vem do servidor por nome. Ficam aqui para que um fluxo
     * salvo antes não as entregue ao adapter.
     */
    public static final Set<String> DISCARDED_DESIGNER_KEYS = Set.of(
            "pineconeApiKey", "pineconeEnvironment", "vectorRedisUrl");

    /**
     * Chaves do designer lidas por outro caminho que não o adapter: a base de conhecimento (RCHFLW-55,
     * {@code KnowledgeNodeComponent}) e a dimensão informativa do embedding da OpenAI (o modelo fixa a
     * dimensão; o adapter não a lê).
     */
    public static final Set<String> NOT_FOR_THE_ADAPTER = Set.of("knowledgeBaseId", "embeddingDimensions");

    private DesignerAdapterKeys() {
    }

    /**
     * O provider de um nó de dados: o {@code provider} explícito (YAML/API), senão o campo do designer
     * ({@code vectorBackend}, {@code embeddingProvider}, {@code memoryBackend}), senão {@code fallback}
     * — o {@code componentId} legado.
     */
    public static String providerOf(String adapterType, Map<String, Object> config, String fallback) {
        String explicit = texto(config == null ? null : config.get("provider"));
        if (explicit != null) {
            return explicit;
        }
        String field = PROVIDER_FIELD_BY_ADAPTER_TYPE.get(adapterType);
        String designer = field == null || config == null ? null : texto(config.get(field));
        return designer != null ? designer : fallback;
    }

    /** O campo do designer que escolhe o provider deste tipo de adapter, ou {@code null}. */
    public static String providerField(String adapterType) {
        return PROVIDER_FIELD_BY_ADAPTER_TYPE.get(adapterType);
    }

    /**
     * A config do nó com as chaves do designer trocadas pelas do adapter. Não falha: um número que não
     * é número fica como veio e o {@link LangChainAdapterComponent} recusa na execução, dizendo o campo
     * ({@link #numericProblem}) — abrir ou listar o fluxo não pode explodir.
     */
    public static Map<String, Object> translate(String adapterType, String provider, Map<String, Object> config) {
        Map<String, Object> out = new LinkedHashMap<>(config == null ? Map.of() : config);
        out.values().removeIf(Objects::isNull);
        DISCARDED_DESIGNER_KEYS.forEach(out::remove);
        String providerField = PROVIDER_FIELD_BY_ADAPTER_TYPE.get(adapterType);
        if (providerField != null) {
            out.remove(providerField);
        }
        apply(out, adapterType, provider);
        if ("chain".equals(adapterType)) {
            // O RagChainAdapter cria o embedding e o vector store com o MESMO mapa de propriedades: as chaves
            // deles entram aqui, pelo provider escolhido para cada papel.
            apply(out, "vectorstore", texto(out.get("vectorstore.provider")));
            apply(out, "embedding", texto(out.get("embedding.provider")));
        }
        return out;
    }

    private static void apply(Map<String, Object> out, String adapterType, String provider) {
        for (Rule rule : RULES) {
            if (!rule.adapterType().equals(adapterType)
                    || (rule.provider() != null && !rule.provider().equals(provider))
                    || !out.containsKey(rule.designerKey())) {
                continue;
            }
            Object value = out.remove(rule.designerKey());
            if (value instanceof String s && s.isBlank()) {
                continue;
            }
            out.put(rule.adapterKey(), convert(value, rule.kind()));
        }
    }

    private static Object convert(Object value, Kind kind) {
        if (kind == Kind.TEXT) {
            return value instanceof String s ? s.trim() : value;
        }
        Number number = value instanceof Number n ? n : parse(value);
        if (number == null) {
            return value;
        }
        if (kind == Kind.INTEGER) {
            return number.doubleValue() == Math.rint(number.doubleValue()) ? (Object) number.intValue() : value;
        }
        return number.doubleValue();
    }

    private static Number parse(Object value) {
        if (!(value instanceof String s)) {
            return null;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Um número do designer que não virou número (texto, fração num campo inteiro): a mensagem diz o
     * campo do painel e o valor, em vez do {@code ClassCastException} de dentro do adapter.
     *
     * @return {@code null} quando está tudo certo
     */
    static String numericProblem(Map<String, Object> properties) {
        for (Rule rule : RULES) {
            if (rule.kind() == Kind.TEXT || !properties.containsKey(rule.adapterKey())) {
                continue;
            }
            Object value = properties.get(rule.adapterKey());
            boolean ok = rule.kind() == Kind.INTEGER ? value instanceof Integer : value instanceof Double;
            if (!ok) {
                return "'" + rule.designerKey() + "' (" + rule.adapterKey() + ") deve ser "
                        + (rule.kind() == Kind.INTEGER ? "um número inteiro" : "um número")
                        + ", veio \"" + value + "\"";
            }
        }
        return null;
    }

    /**
     * Todas as chaves do designer que esta camada conhece para um tipo de adapter: as traduzidas, o campo
     * de provider, as descartadas e as lidas por outro caminho. É o que o teste de conformidade compara
     * com o painel.
     */
    public static Set<String> knownDesignerKeys(String adapterType) {
        Set<String> keys = new LinkedHashSet<>();
        String field = PROVIDER_FIELD_BY_ADAPTER_TYPE.get(adapterType);
        if (field != null) {
            keys.add(field);
        }
        for (Rule rule : RULES) {
            boolean subAdapterDoRag = "chain".equals(adapterType)
                    && List.of("vectorstore", "embedding").contains(rule.adapterType());
            if (rule.adapterType().equals(adapterType) || subAdapterDoRag) {
                keys.add(rule.designerKey());
            }
        }
        keys.addAll(DISCARDED_DESIGNER_KEYS);
        keys.addAll(NOT_FOR_THE_ADAPTER);
        return keys;
    }

    private static String texto(Object value) {
        return value instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
