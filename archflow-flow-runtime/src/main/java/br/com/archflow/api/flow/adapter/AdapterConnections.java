package br.com.archflow.api.flow.adapter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

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

    /** Nenhuma conexão: toda busca falha dizendo o que definir (testes, standalone). */
    public static final AdapterConnections NONE = new AdapterConnections(key -> null);

    private final Function<String, String> property;

    private AdapterConnections(Function<String, String> property) {
        this.property = property;
    }

    /**
     * @param property leitura de uma propriedade do servidor — no host, {@code Environment::getProperty}
     *                 (que também aceita a forma de variável de ambiente, {@code ARCHFLOW_ADAPTERS_...})
     */
    public static AdapterConnections of(Function<String, String> property) {
        return property == null ? NONE : new AdapterConnections(property);
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
