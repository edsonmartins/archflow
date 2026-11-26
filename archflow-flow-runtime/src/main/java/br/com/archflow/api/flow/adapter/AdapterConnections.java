package br.com.archflow.api.flow.adapter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * Conexões dos adapters de dados (pgvector, redis, pinecone) declaradas na configuração do servidor,
 * por nome: {@code archflow.adapters.<provider>.<nome>.<campo>} (RCHFLW-56).
 *
 * <p><b>Por que por nome, e não no nó.</b> O designer gravava URL, usuário, senha e chave do banco
 * vetorial no JSON do fluxo — que vai para o YAML, para o export standalone, para o contexto do
 * copiloto e para quem tiver permissão de editar o fluxo. Quem edita o fluxo também escolheria para
 * onde a chave vai. Agora o nó só diz <i>qual</i> conexão usar ({@code pgDatasource},
 * {@code redisConnection}, {@code pineconeConnection}) e o servidor diz o resto; as chaves de
 * credencial que vierem no JSON são descartadas ({@link #SERVER_ONLY_PROPERTIES}).</p>
 *
 * <p>Sem a conexão configurada, a falha diz a propriedade a definir — e acontece na execução, não ao
 * abrir o fluxo no designer (a criação do adapter é preguiçosa, ver {@link LangChainAdapterComponent}).</p>
 *
 * <p><b>As conexões NÃO são por tenant.</b> O nome é resolvido só no servidor, sem olhar o tenant da
 * execução: um fluxo de qualquer tenant que escreva {@code pgDatasource: "x"} usa a conexão
 * {@code archflow.adapters.pgvector.x.*}, com as credenciais do servidor — inclusive nas operações
 * destrutivas que os adapters aceitam com {@code operation} escrita à mão ({@code remove},
 * {@code removeAll}), e as tabelas dos adapters não têm coluna de tenant. Antes, alcançar um banco exigia
 * ter as credenciais dele no JSON; agora o servidor as empresta a quem nomear a conexão. Num deployment
 * multi-tenant, declare aqui só conexões que todo tenant pode usar; dados de um tenant ficam na Base de
 * conhecimento ({@code knowledgeBaseId}), que é isolada por tenant. Escopo por tenant é trabalho em
 * aberto, não está decidido.</p>
 *
 * <p><b>A memória JDBC usa o banco do próprio servidor (RCHFLW-119).</b> O {@code JdbcMemoryAdapter} espera um
 * objeto {@link DataSource}, que não cabe numa propriedade de texto nem num JSON: quem o entrega é o host, o
 * mesmo banco da persistência JDBC ({@code archflow.persistence.jdbc.enabled=true}), onde as migrações
 * {@code V3_x} do {@code archflow-langchain4j-memory-jdbc} criam a tabela. Não há conexão por nome aqui — a tabela
 * tem as colunas de tenant e sessão, então o isolamento é o do adapter, e não um banco emprestado. Sem a
 * persistência JDBC, o nó falha dizendo isso ({@link #memoryDataSource}); não cai em outro backend.</p>
 */
public final class AdapterConnections {

    /** Prefixo das propriedades no servidor. */
    public static final String PREFIX = "archflow.adapters.";

    /**
     * Campo da conexão no servidor → propriedade que o adapter lê, por provider. A ordem é a da
     * mensagem de erro. {@code required=false} é um campo que o adapter tem padrão próprio para.
     */
    record Field(String serverField, String adapterProperty, boolean required, boolean integer) {
    }

    static final Map<String, List<Field>> FIELDS_BY_PROVIDER = Map.of(
            "pgvector", List.of(
                    new Field("jdbc-url", "pgvector.jdbcUrl", true, false),
                    new Field("username", "pgvector.username", true, false),
                    new Field("password", "pgvector.password", true, false)),
            "redis", List.of(
                    new Field("host", "redis.host", true, false),
                    new Field("port", "redis.port", false, true)),
            "pinecone", List.of(
                    new Field("api-url", "pinecone.apiUrl", true, false),
                    new Field("api-key", "pinecone.apiKey", true, false)));

    /**
     * Propriedades de adapter que só a conexão do servidor preenche. Vindas do JSON do nó, são
     * removidas antes de criar o adapter: credencial e destino não moram no workflow. O
     * {@code datasource} é o objeto que o adapter JDBC de memória espera — nunca vem de um JSON.
     */
    public static final List<String> SERVER_ONLY_PROPERTIES = List.of(
            "pgvector.jdbcUrl", "pgvector.username", "pgvector.password",
            "redis.host", "redis.port", "redis.password",
            "pinecone.apiUrl", "pinecone.apiKey",
            "datasource");

    /** A propriedade que liga a persistência JDBC do servidor — e, com ela, o banco da memória JDBC. */
    public static final String JDBC_PERSISTENCE_PROPERTY = "archflow.persistence.jdbc.enabled";

    /**
     * Nenhuma conexão: toda busca falha dizendo o que definir. É o que têm os runtimes sem servidor — o standalone e
     * o runner embutido do SDK ({@code PortableFlowStepFactory}) — e os testes.
     */
    public static final AdapterConnections NONE = new AdapterConnections(key -> null, () -> null, false);

    private final Function<String, String> property;
    private final Supplier<DataSource> serverDataSource;
    // Sem servidor não há persistência JDBC a ligar: a mensagem da memória JDBC tem de dizer onde o nó roda, e não
    // mandar ligar uma propriedade que o standalone e o SDK não leem.
    private final boolean server;

    private AdapterConnections(Function<String, String> property, Supplier<DataSource> serverDataSource,
                               boolean server) {
        this.property = property;
        this.serverDataSource = serverDataSource;
        this.server = server;
    }

    /**
     * @param property leitura de uma propriedade do servidor — no host, {@code Environment::getProperty}
     *                 (que também aceita a forma de variável de ambiente, {@code ARCHFLOW_ADAPTERS_...})
     */
    public static AdapterConnections of(Function<String, String> property) {
        return of(property, null);
    }

    /**
     * @param serverDataSource o banco da persistência JDBC do servidor, para a memória JDBC; lido só na execução
     *                         (o adapter nasce preguiçoso), e nulo — ou devolvendo nulo — quando a persistência
     *                         JDBC não está ligada
     */
    public static AdapterConnections of(Function<String, String> property, Supplier<DataSource> serverDataSource) {
        if (property == null && serverDataSource == null) {
            return NONE;
        }
        return new AdapterConnections(property != null ? property : key -> null,
                serverDataSource != null ? serverDataSource : () -> null, true);
    }

    /**
     * O banco do servidor para a memória JDBC do nó {@code nodeId}.
     *
     * @throws IllegalStateException sem a persistência JDBC do servidor — a mensagem diz o que ligar, ou, fora do
     *                               servidor, que o nó só roda nele
     */
    public DataSource memoryDataSource(String nodeId) {
        DataSource dataSource = serverDataSource.get();
        if (dataSource == null && !server) {
            throw new IllegalStateException("[ADAPTER-CONEXAO] Nó " + nodeId + " (jdbc): a memória JDBC grava no "
                    + "banco do servidor archflow, e este runtime não tem um — o standalone e o runner embutido do SDK "
                    + "não recebem o DataSource do servidor. Execute este fluxo no servidor archflow, com "
                    + JDBC_PERSISTENCE_PROPERTY + "=true.");
        }
        if (dataSource == null) {
            throw new IllegalStateException("[ADAPTER-CONEXAO] Nó " + nodeId + " (jdbc): a memória JDBC grava no "
                    + "banco do servidor, e a persistência JDBC do servidor não está ligada. Ligue "
                    + JDBC_PERSISTENCE_PROPERTY + "=true (env " + envName(JDBC_PERSISTENCE_PROPERTY)
                    + ") com o spring.datasource.* do banco, ou escolha o backend redis no nó.");
        }
        return dataSource;
    }

    /** {@code true} quando os adapters deste provider precisam de uma conexão do servidor. */
    public static boolean needsConnection(String provider) {
        return provider != null && FIELDS_BY_PROVIDER.containsKey(provider);
    }

    /**
     * As propriedades do adapter que a conexão {@code name} do {@code provider} fornece.
     *
     * @param nodeId para a mensagem — qual nó pediu
     * @param name   o nome escolhido no nó; vazio é erro (o nó não escolheu conexão)
     * @param field  o campo do designer onde o nome é escolhido, para a mensagem
     * @throws IllegalStateException sem nome, ou com um campo obrigatório ausente no servidor
     */
    public Map<String, Object> resolve(String nodeId, String provider, String name, String field) {
        List<Field> fields = FIELDS_BY_PROVIDER.get(provider);
        if (fields == null) {
            return Map.of();
        }
        String exemplo = PREFIX + provider + ".<nome>." + fields.get(0).serverField();
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("[ADAPTER-CONEXAO] Nó " + nodeId + " (" + provider + "): escolha a "
                    + "conexão do servidor no campo '" + field + "'. As credenciais não vão no fluxo: o servidor "
                    + "as declara em " + exemplo + " e o nó só diz o nome.");
        }
        String nome = name.trim();
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Field f : fields) {
            String key = PREFIX + provider + "." + nome + "." + f.serverField();
            String value = property.apply(key);
            if (value == null || (value.isBlank() && !"password".equals(f.serverField()))) {
                if (!f.required()) {
                    continue;
                }
                throw new IllegalStateException("[ADAPTER-CONEXAO] Nó " + nodeId + " (" + provider + "): a conexão '"
                        + nome + "' não está configurada no servidor. Defina " + key + " (env "
                        + envName(key) + ")" + outrosCampos(provider, nome, f) + ".");
            }
            if (f.integer()) {
                try {
                    resolved.put(f.adapterProperty(), Integer.parseInt(value.trim()));
                } catch (NumberFormatException e) {
                    throw new IllegalStateException("[ADAPTER-CONEXAO] " + key + " deve ser um número inteiro, veio \""
                            + value + "\".");
                }
            } else {
                resolved.put(f.adapterProperty(), value);
            }
        }
        return resolved;
    }

    /** Os demais campos obrigatórios da conexão, para a mensagem dizer tudo de uma vez. */
    private static String outrosCampos(String provider, String nome, Field faltando) {
        List<String> outros = FIELDS_BY_PROVIDER.get(provider).stream()
                .filter(f -> f.required() && f != faltando)
                .map(f -> PREFIX + provider + "." + nome + "." + f.serverField())
                .toList();
        return outros.isEmpty() ? "" : " — a conexão também precisa de " + String.join(", ", outros);
    }

    /** A forma de variável de ambiente que o Spring aceita para a propriedade. */
    static String envName(String key) {
        return key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }
}
