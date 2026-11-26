package br.com.archflow.api.flow.adapter;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.langchain4j.core.spi.LangChainRegistry;
import br.com.archflow.langchain4j.memory.jdbc.JdbcMemoryAdapter;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Contrato comum aos adapters de memória ({@code jdbc} e {@code redis}): a mesma sequência de {@code add} dá o mesmo
 * {@code get}, da mais antiga para a mais nova, o corte guarda as N mais recentes e a conversa é de um tenant e uma
 * sessão.
 *
 * <p>Existe por causa da RCHFLW-118: o redis devolvia a conversa de trás para frente ({@code LPUSH} + {@code LRANGE})
 * e o jdbc na ordem ({@code ORDER BY id ASC}). Cada adapter tinha os seus testes, nenhum conferia o que o outro
 * fazia, e o nó de LLM que lê o histórico recebia uma conversa diferente conforme o backend escolhido no painel. Os
 * dois são criados pelo {@link LangChainRegistry}, como o servidor os cria: o jdbc num H2 com a tabela da migração
 * dele e o redis num container (sem Docker, os casos do redis são pulados, como os outros testes com container).</p>
 */
@DisplayName("contrato dos adapters de memória: jdbc e redis devolvem a mesma conversa")
class MemoriaAdaptersContratoTest {

    enum Backend {
        JDBC("jdbc"), REDIS("redis");

        final String provider;

        Backend(String provider) {
            this.provider = provider;
        }
    }

    private static final JdbcDataSource H2 = new JdbcDataSource();

    static {
        H2.setURL("jdbc:h2:mem:rchflw118;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    }

    /** Sobe só quando um caso do redis roda e há Docker. */
    private static GenericContainer<?> redis;

    private final List<LangChainAdapter> abertos = new ArrayList<>();

    @BeforeAll
    static void tabelaDaMigracao() throws Exception {
        try (InputStream ddl = JdbcMemoryAdapter.class.getResourceAsStream(
                "/db/migration/V3_1__create_chat_memory.sql");
             Connection conn = H2.getConnection();
             Statement st = conn.createStatement()) {
            assertThat(ddl).as("a migração do archflow-langchain4j-memory-jdbc").isNotNull();
            st.execute("DROP TABLE IF EXISTS chat_messages");
            for (String sql : new String(ddl.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
                if (!sql.isBlank()) {
                    st.execute(sql);
                }
            }
        }
    }

    @AfterEach
    void fechar() {
        abertos.forEach(LangChainAdapter::shutdown);
    }

    @AfterAll
    static void pararRedis() {
        if (redis != null) {
            redis.stop();
        }
    }

    private LangChainAdapter adapter(Backend backend, int maxMessages) {
        Map<String, Object> props = new HashMap<>();
        props.put("memory.maxMessages", maxMessages);
        if (backend == Backend.JDBC) {
            props.put("datasource", H2);
        } else {
            assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                    "sem Docker: o contrato do redis não roda aqui");
            synchronized (MemoriaAdaptersContratoTest.class) {
                if (redis == null) {
                    redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
                    redis.start();
                }
            }
            props.put("redis.host", redis.getHost());
            props.put("redis.port", redis.getMappedPort(6379));
            // Um prefixo por adapter: os casos não enxergam as chaves uns dos outros no mesmo container.
            props.put("redis.prefix", "contrato:" + UUID.randomUUID() + ":");
        }
        LangChainAdapter adapter = LangChainRegistry.createAdapter(backend.provider, "memory", props);
        abertos.add(adapter);
        return adapter;
    }

    private static ExecutionContext conversa(String tenant, String sessao) {
        return new DefaultExecutionContext(tenant, "u1", sessao, MessageWindowChatMemory.withMaxMessages(5));
    }

    /** Um tenant por caso: a tabela do H2 é uma só para a classe. */
    private static String tenant() {
        return "t-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Backend.class)
    @DisplayName("a mesma sequência de add dá o mesmo get, da mais antiga para a mais nova")
    void mesmaSequenciaMesmoGet(Backend backend) throws Exception {
        LangChainAdapter memoria = adapter(backend, 100);
        ExecutionContext ctx = conversa(tenant(), "s1");

        memoria.execute("add", SystemMessage.from("você atende a loja"), ctx);
        memoria.execute("add", UserMessage.from("oi"), ctx);
        memoria.execute("add", AiMessage.from("olá, em que posso ajudar?"), ctx);
        memoria.execute("add", UserMessage.from("quero trocar meu plano"), ctx);

        assertThat(textos(memoria.execute("get", null, ctx)))
                .as("antes, no redis: a mais nova primeiro (LPUSH + LRANGE 0 -1)")
                .containsExactly("SYSTEM: você atende a loja", "USER: oi", "AI: olá, em que posso ajudar?",
                        "USER: quero trocar meu plano");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Backend.class)
    @DisplayName("o corte guarda as N mais recentes, ainda em ordem")
    void corteGuardaAsMaisRecentes(Backend backend) throws Exception {
        LangChainAdapter memoria = adapter(backend, 3);
        ExecutionContext ctx = conversa(tenant(), "s1");

        for (int i = 1; i <= 5; i++) {
            memoria.execute("add", UserMessage.from("m" + i), ctx);
        }

        assertThat(textos(memoria.execute("get", null, ctx))).containsExactly("USER: m3", "USER: m4", "USER: m5");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Backend.class)
    @DisplayName("a conversa é do tenant e da sessão; o clear só apaga a dela")
    void isolamentoPorTenantESessao(Backend backend) throws Exception {
        LangChainAdapter memoria = adapter(backend, 100);
        String a = tenant();
        String b = tenant();

        memoria.execute("add", UserMessage.from("de A na s1"), conversa(a, "s1"));
        memoria.execute("add", UserMessage.from("de B na s1"), conversa(b, "s1"));
        memoria.execute("add", UserMessage.from("de A na s2"), conversa(a, "s2"));

        assertThat(textos(memoria.execute("get", null, conversa(a, "s1")))).containsExactly("USER: de A na s1");
        assertThat(textos(memoria.execute("get", null, conversa(b, "s1")))).containsExactly("USER: de B na s1");
        assertThat(textos(memoria.execute("get", null, conversa(a, "s2")))).containsExactly("USER: de A na s2");

        memoria.execute("clear", null, conversa(a, "s1"));
        assertThat(textos(memoria.execute("get", null, conversa(a, "s1")))).isEmpty();
        assertThat(textos(memoria.execute("get", null, conversa(b, "s1")))).containsExactly("USER: de B na s1");
        assertThat(textos(memoria.execute("get", null, conversa(a, "s2")))).containsExactly("USER: de A na s2");
    }

    /** "TIPO: texto" de cada mensagem, na ordem em que o adapter devolveu. */
    private static List<String> textos(Object historico) {
        assertThat(historico).isInstanceOf(List.class);
        List<String> out = new ArrayList<>();
        for (Object item : (List<?>) historico) {
            ChatMessage m = (ChatMessage) item;
            String texto = switch (m) {
                case UserMessage u -> u.singleText();
                case AiMessage ai -> ai.text();
                case SystemMessage s -> s.text();
                default -> m.toString();
            };
            out.add(m.type() + ": " + texto);
        }
        return out;
    }
}
