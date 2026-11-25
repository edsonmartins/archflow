package br.com.archflow.api.flow.adapter;

import br.com.archflow.langchain4j.chain.rag.RagChainAdapter;
import br.com.archflow.langchain4j.embedding.openai.OpenAiEmbeddingAdapter;
import br.com.archflow.langchain4j.memory.redis.RedisMemoryAdapter;
import br.com.archflow.langchain4j.provider.TenantKeyResolver;
import br.com.archflow.langchain4j.vectorstore.pgvector.PgVectorStoreAdapter;
import br.com.archflow.langchain4j.vectorstore.pinecone.PineconeVectorStoreAdapter;
import br.com.archflow.langchain4j.vectorstore.redis.RedisVectorStoreAdapter;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RCHFLW-56 — o que o painel grava nos nós de dados chega ao adapter com o nome que ele lê.
 *
 * <p>Duas metades, como o {@code AdapterNodeTypesMirrorTest}: (1) toda chave que o painel grava nas seções de
 * Vector, Embedding, Memória e RAG está na {@link DesignerAdapterKeys} — traduzida, descartada de propósito ou
 * lida por outro caminho; um campo novo no painel sem tradução quebra aqui, em vez de virar uma chave que nenhum
 * Java lê. (2) A config de um nó como o designer a grava, depois da tradução e da ponte, passa pelo
 * {@code validate} do adapter <b>de verdade</b> — conferir só o mapa traduzido não provaria que o adapter
 * aceita.</p>
 *
 * <p>Credencial e destino vêm da conexão do servidor, por nome; o que vier no JSON do nó é descartado.</p>
 */
@DisplayName("chaves do designer → adapters LangChain4j")
class DesignerAdapterKeysConformanceTest {

    private static final Path UI = Path.of("..", "archflow-ui", "src", "components");
    private static final Path PANEL = UI.resolve("PropertyPanel.tsx");
    private static final Path VECTOR_FIELDS = UI.resolve(Path.of("PropertyPanel", "fields", "VectorStoreFields.tsx"));
    private static final Path KB_FIELD = UI.resolve(Path.of("PropertyPanel", "fields", "KnowledgeBaseField.tsx"));
    private static final Path DATA_KEYS = UI.resolve(Path.of("FlowCanvas", "dataNodeKeys.ts"));

    /** {@code storedX(nodeData, 'k')}, {@code update('k'}, {@code choose('k')}, {@code num('k')}. */
    private static final Pattern KEY = Pattern.compile(
            "(?:stored(?:Text|Choice|Number|Flag)\\(nodeData, |update\\(|choose\\(|num\\(|numberUpdater\\(update, )"
                    + "'([A-Za-z]+)'");

    private static final Map<String, Object> SERVER = Map.of(
            "archflow.adapters.pgvector.principal.jdbc-url", "jdbc:postgresql://db.interno:5432/vetores",
            "archflow.adapters.pgvector.principal.username", "archflow",
            // Valores fictícios de teste (falso positivo do scanner de segredos).
            "archflow.adapters.pgvector.principal.password", "s3nha", // ggignore
            "archflow.adapters.redis.cache.host", "redis.interno",
            "archflow.adapters.redis.cache.port", "6380",
            "archflow.adapters.pinecone.pc.api-url", "https://idx.svc.pinecone.io",
            "archflow.adapters.pinecone.pc.api-key", "pc-chave-do-servidor");

    private static final AdapterConnections CONNECTIONS = AdapterConnections.of(k -> (String) SERVER.get(k));

    /** A chave do tenant para a OpenAI — o que o resolver de produção devolve. */
    private static final TenantKeyResolver KEYS = (tenant, provider) ->
            "openai".equals(provider) ? Optional.of("sk-tenant-" + tenant) : Optional.empty();

    private static String section(String source, String banner) {
        int start = source.indexOf(banner);
        assertThat(start).as("seção %s no PropertyPanel.tsx", banner).isNotNegative();
        int end = source.indexOf("{/* ════", source.indexOf("\n", start) + 1);
        // O banner tem duas linhas de moldura: pula a de baixo.
        end = source.indexOf("{/* ════", end + 1);
        return source.substring(start, end < 0 ? source.length() : end);
    }

    private static Set<String> keys(String text) {
        Matcher m = KEY.matcher(text);
        Set<String> out = new TreeSet<>();
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** Config do nó → tradução do factory → propriedades efetivas da ponte, sem rede. */
    private static Map<String, Object> effective(String adapterType, Map<String, Object> designer) {
        String provider = DesignerAdapterKeys.providerOf(adapterType, designer, "rag");
        Map<String, Object> translated = DesignerAdapterKeys.translate(adapterType, provider, designer);
        return new LangChainAdapterComponent(provider, adapterType, translated, KEYS, null, null, false, List.of(),
                null, CONNECTIONS).effectiveProperties("acme");
    }

    @Test
    @DisplayName("toda chave que o painel grava nos nós de dados está na tabela de tradução")
    void painelEspelhaATabela() throws Exception {
        String panel = Files.readString(PANEL);
        Set<String> vectorStore = keys(Files.readString(VECTOR_FIELDS));
        assertThat(vectorStore).as("campos do VectorStoreFields").contains("vectorBackend", "pgDimensions");
        // O KnowledgeBaseField grava pela constante: confere que a constante é a chave que o motor lê.
        assertThat(Files.readString(KB_FIELD)).contains("update(KNOWLEDGE_BASE_KEY");
        assertThat(Files.readString(DATA_KEYS))
                .contains("KNOWLEDGE_BASE_KEY = '" + br.com.archflow.api.flow.KnowledgeNodeComponent.BASE_KEY + "'")
                .contains("LEGACY_KNOWLEDGE_BASE_KEY = '"
                        + br.com.archflow.api.flow.KnowledgeNodeComponent.LEGACY_BASE_KEY + "'");
        assertThat(DesignerAdapterKeys.CONNECTION_FIELD_BY_PROVIDER.values())
                .allSatisfy(field -> assertThat(Files.readString(DATA_KEYS)).contains("'" + field + "'"));

        Map<String, Set<String>> porTipo = new LinkedHashMap<>();
        porTipo.put("vectorstore", union(keys(section(panel, "VECTOR STORE FIELDS")), vectorStore));
        porTipo.put("embedding", keys(section(panel, "EMBEDDING FIELDS")));
        porTipo.put("memory", union(keys(section(panel, "MEMORY FIELDS")), Set.of("redisConnection")));
        porTipo.put("chain", union(keys(section(panel, "RAG CHAIN FIELDS")), vectorStore));

        Map<String, Set<String>> semTraducao = new LinkedHashMap<>();
        porTipo.forEach((tipo, chaves) -> {
            assertThat(chaves).as("chaves do painel para %s", tipo).isNotEmpty();
            Set<String> faltando = new TreeSet<>(chaves);
            faltando.removeAll(DesignerAdapterKeys.knownDesignerKeys(tipo));
            if (!faltando.isEmpty()) {
                semTraducao.put(tipo, faltando);
            }
        });
        assertThat(semTraducao)
                .as("chave gravada pelo painel que nenhum adapter leria — traduza em DesignerAdapterKeys")
                .isEmpty();
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new TreeSet<>(a);
        out.addAll(b);
        return out;
    }

    @Test
    @DisplayName("vector store pgvector: o validate do adapter aceita, com o banco da conexão do servidor")
    void pgvector() {
        Map<String, Object> props = effective("vectorstore", Map.of("vectorBackend", "pgvector",
                "pgDatasource", "principal", "pgTableName", "docs", "pgDimensions", 1536, "topK", 4));

        assertThatCode(() -> new PgVectorStoreAdapter().validate(props)).doesNotThrowAnyException();
        assertThat(props).containsEntry("pgvector.jdbcUrl", "jdbc:postgresql://db.interno:5432/vetores")
                .containsEntry("pgvector.username", "archflow")
                .containsEntry("pgvector.table", "docs")
                .containsEntry("pgvector.dimension", 1536)
                .doesNotContainKeys("vectorBackend", "pgDatasource", "pgDimensions");
    }

    @Test
    @DisplayName("vector store redis e pinecone: idem, com host/URL/chave do servidor")
    void redisEPinecone() {
        Map<String, Object> redis = effective("vectorstore", Map.of("vectorBackend", "redis",
                "redisConnection", "cache", "vectorRedisPrefix", "kb:", "vectorDimensions", 128));
        assertThatCode(() -> new RedisVectorStoreAdapter().validate(redis)).doesNotThrowAnyException();
        assertThat(redis).containsEntry("redis.host", "redis.interno").containsEntry("redis.port", 6380)
                .containsEntry("redis.prefix", "kb:").containsEntry("redis.dimension", 128);

        Map<String, Object> pinecone = effective("vectorstore", Map.of("vectorBackend", "pinecone",
                "pineconeConnection", "pc", "pineconeIndex", "faq", "vectorDimensions", "1536",
                "pineconeApiKey", "sk-escrita-no-no"));
        assertThatCode(() -> new PineconeVectorStoreAdapter().validate(pinecone)).doesNotThrowAnyException();
        assertThat(pinecone).containsEntry("pinecone.apiKey", "pc-chave-do-servidor")
                .containsEntry("pinecone.indexName", "faq").containsEntry("pinecone.dimension", 1536)
                .doesNotContainKey("pineconeApiKey");
    }

    @Test
    @DisplayName("embedding openai: a chave do tenant vai em openai.api.key e o modelo em openai.model")
    void embeddingOpenAi() {
        Map<String, Object> props = effective("embedding", Map.of("embeddingProvider", "openai",
                "embeddingModel", "text-embedding-3-small", "embeddingDimensions", 1536));

        assertThatCode(() -> new OpenAiEmbeddingAdapter().validate(props)).doesNotThrowAnyException();
        assertThat(props).containsEntry("openai.api.key", "sk-tenant-acme")
                .containsEntry("openai.model", "text-embedding-3-small");
    }

    @Test
    @DisplayName("memória redis: host do servidor e memory.maxMessages")
    void memoriaRedis() {
        Map<String, Object> props = effective("memory", Map.of("memoryBackend", "redis",
                "redisConnection", "cache", "memoryMaxMessages", 50));

        assertThatCode(() -> new RedisMemoryAdapter().validate(props)).doesNotThrowAnyException();
        assertThat(props).containsEntry("redis.host", "redis.interno").containsEntry("memory.maxMessages", 50);
    }

    @Test
    @DisplayName("RAG: os três providers, o retriever e as chaves de cada sub-adapter no mesmo mapa")
    void rag() {
        Map<String, Object> props = effective("chain", Map.of("embeddingProvider", "openai",
                "vectorBackend", "pgvector", "llmProvider", "openai", "pgDatasource", "principal",
                "pgDimensions", 1536, "retrieverMaxResults", 3, "retrieverMinScore", 1));

        assertThatCode(() -> new RagChainAdapter().validate(props))
                .as("antes: 'Embedding provider is required' — o painel gravava embeddingProvider")
                .doesNotThrowAnyException();
        // O RagChainAdapter cria o embedding e o vector store com este mesmo mapa.
        assertThatCode(() -> new OpenAiEmbeddingAdapter().validate(props)).doesNotThrowAnyException();
        assertThatCode(() -> new PgVectorStoreAdapter().validate(props)).doesNotThrowAnyException();
        assertThat(props).containsEntry("embedding.provider", "openai")
                .containsEntry("vectorstore.provider", "pgvector")
                .containsEntry("languagemodel.provider", "openai")
                .containsEntry("retriever.maxResults", 3)
                .containsEntry("retriever.minScore", 1.0)
                .containsEntry("api.key", "sk-tenant-acme")
                .containsEntry("openai.api.key", "sk-tenant-acme");
    }

    @Test
    @DisplayName("credencial e destino escritos no JSON do nó não chegam ao adapter")
    void credencialDoJsonEDescartada() {
        Map<String, Object> props = effective("vectorstore", Map.of("vectorBackend", "pgvector",
                "pgDatasource", "principal", "pgDimensions", 8,
                "pgvector.jdbcUrl", "jdbc:postgresql://servidor-de-quem-edita/x",
                "pgvector.password", "da-mao", "vectorRedisUrl", "redis://:pw@fora:6379")); // ggignore

        assertThat(props).containsEntry("pgvector.jdbcUrl", "jdbc:postgresql://db.interno:5432/vetores")
                .containsEntry("pgvector.password", "s3nha")
                .doesNotContainKey("vectorRedisUrl");
    }

    @Test
    @DisplayName("sem conexão no servidor, a falha diz a propriedade a definir — e o JSON não supre")
    void semConexaoNoServidor() {
        Map<String, Object> designer = Map.of("vectorBackend", "pgvector", "pgDatasource", "outra",
                "pgDimensions", 8, "pgvector.jdbcUrl", "jdbc:postgresql://x/y",
                "pgvector.username", "u", "pgvector.password", "p");

        assertThatThrownBy(() -> effective("vectorstore", designer))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("archflow.adapters.pgvector.outra.jdbc-url")
                .hasMessageContaining("ARCHFLOW_ADAPTERS_PGVECTOR_OUTRA_JDBC_URL")
                .hasMessageContaining("archflow.adapters.pgvector.outra.username");

        assertThatThrownBy(() -> effective("vectorstore", Map.of("vectorBackend", "redis", "vectorDimensions", 8)))
                .hasMessageContaining("campo 'redisConnection'")
                .hasMessageContaining("archflow.adapters.redis.<nome>.host");
    }

    @Test
    @DisplayName("número inválido diz o campo do painel; memória JDBC diz que não recebe DataSource")
    void mensagensClaras() {
        assertThatThrownBy(() -> effective("vectorstore", Map.of("vectorBackend", "pgvector",
                "pgDatasource", "principal", "pgDimensions", "muitas")))
                .hasMessageContaining("'pgDimensions'");
        assertThatThrownBy(() -> effective("memory", Map.of("memoryBackend", "jdbc")))
                .hasMessageContaining("memória JDBC")
                .hasMessageContaining("DataSource");
    }

    /** O componente como o factory o monta para um nó do designer: o tipo do nó decide a operação padrão. */
    private static LangChainAdapterComponent node(String provider, String adapterType, Map<String, Object> config,
                                                  String nodeType) {
        return new LangChainAdapterComponent(provider, adapterType, config, KEYS, null, null, false, List.of(),
                null, CONNECTIONS, nodeType);
    }

    @Test
    @DisplayName("operação padrão de cada TIPO DE NÓ de dados e a entrada que o adapter exige")
    void operacaoEEntrada() {
        var embedding = node("openai", "embedding", Map.of(), "embedding");
        assertThat(embedding.dataOperation("execute")).isEqualTo("embed");
        assertThat(embedding.dataInput("embed", Map.of("input", "olá"))).isEqualTo("olá");
        assertThat(node("rag", "chain", Map.of(), "rag").dataOperation(null)).isEqualTo("query");
        assertThatThrownBy(() -> node("redis", "memory", Map.of(), "memory").dataOperation("execute"))
                .hasMessageContaining("add, get ou clear");

        var store = node("pgvector", "vectorstore",
                DesignerAdapterKeys.translate("vectorstore", "pgvector", Map.of("topK", 2, "minScore", "0.5")),
                "vector-search");
        assertThat(store.dataOperation("execute")).isEqualTo("search");
        EmbeddingSearchRequest request = store.searchRequest(Map.of("vector", List.of(0.1, 0.2), "dimension", 2));
        assertThat(request.maxResults()).isEqualTo(2);
        assertThat(request.minScore()).isEqualTo(0.5);
        assertThatThrownBy(() -> store.searchRequest("texto"))
                .hasMessageContaining("nó Embedding")
                .hasMessageContaining("Base de conhecimento");

        Object vetor = LangChainAdapterComponent.dataOutput(
                dev.langchain4j.model.output.Response.from(dev.langchain4j.data.embedding.Embedding.from(
                        new float[] {1f, 2f})));
        assertThat(vetor).isEqualTo(Map.of("vector", List.of(1f, 2f), "dimension", 2));
    }

    @Test
    @DisplayName("vector-store ('Armazena embeddings') não vira busca: sem operation recusa, e não sugere base")
    void vectorStoreNaoBuscaCalado() {
        var store = node("pgvector", "vectorstore", DesignerAdapterKeys.translate("vectorstore", "pgvector",
                Map.of("pgDatasource", "principal", "pgDimensions", 1536)), "vector-store");

        assertThatThrownBy(() -> store.dataOperation("execute"))
                .as("antes: a operação padrão do adapter vectorstore era search — o nó buscava e dava sucesso")
                .hasMessageContaining("'operation'")
                .hasMessageContaining("search, remove ou removeAll")
                .hasMessageContaining("Nenhum adapter de vector store grava vetores");
        assertThat(store.dataOperation("search")).isEqualTo("search");
        // O vector-store não tem seletor de base (KnowledgeNodeComponent.NODE_TYPES): o conselho seria inseguível.
        assertThatThrownBy(() -> store.searchRequest("texto"))
                .hasMessageContaining("nó Embedding")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("Base de conhecimento"));
        // Sem tipo de nó (quem monta o componente à mão) também não há padrão adivinhado.
        assertThatThrownBy(() -> new LangChainAdapterComponent("pgvector", "vectorstore", Map.of(), KEYS)
                .dataOperation("execute"))
                .hasMessageContaining("search, remove ou removeAll");
    }

    @Test
    @DisplayName("os nós sem operação padrão são os mesmos no motor e no editor (Executar e copiloto)")
    void semOperacaoPadraoEspelhado() throws Exception {
        Matcher m = Pattern.compile("NODE_TYPES_WITHOUT_DEFAULT_OPERATION[^=]*=\\s*new Set\\(\\[([^\\]]*)\\]\\)")
                .matcher(Files.readString(DATA_KEYS));
        assertThat(m.find()).as("NODE_TYPES_WITHOUT_DEFAULT_OPERATION em dataNodeKeys.ts").isTrue();
        Set<String> ui = new TreeSet<>();
        Matcher item = Pattern.compile("'([^']+)'").matcher(m.group(1));
        while (item.find()) {
            ui.add(item.group(1));
        }
        // Os nós de dados da paleta (o "vectorstore" é só um alias de YAML escrito à mão, fora da paleta).
        Set<String> motor = new TreeSet<>();
        for (String nodeType : List.of("embedding", "memory", "vector-store", "vector-search", "rag")) {
            assertThat(AdapterNodeTypes.adapterTypeFor(nodeType)).as(nodeType).isNotNull();
            if (!LangChainAdapterComponent.DEFAULT_OPERATION_BY_NODE_TYPE.containsKey(nodeType)) {
                motor.add(nodeType);
            }
        }
        assertThat(ui).isEqualTo(motor);
    }
}
