package br.com.archflow.api.flow;

import br.com.archflow.agent.persistence.InMemoryFlowRepository;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.langchain4j.core.spi.LangChainAdapterFactory;
import br.com.archflow.langchain4j.memory.jdbc.JdbcMemoryAdapter;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.plugin.api.catalog.DefaultComponentCatalog;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * RCHFLW-115 — o nó Memória montado no designer executa: um fluxo com {@code add} e depois {@code get} roda no motor
 * de verdade e devolve o que guardou.
 *
 * <p>Antes o painel não tinha o campo Operação e o nó falhava sempre ("diga a operação em 'operation'"). Só o campo
 * não bastava: os adapters de memória exigem um {@code ChatMessage} ("Input must be a ChatMessage") e devolvem
 * {@code null} no {@code add}, que as variáveis da execução recusam. O backend é o {@link JdbcMemoryAdapter} de
 * verdade, com a tabela da migração dele, num H2 — o provider {@code jdbc} do servidor ainda não recebe um
 * DataSource no fluxo (o painel avisa), então o teste registra o mesmo adapter sob outro nome, com o banco
 * entregue pela factory.</p>
 */
@DisplayName("nó Memória do designer: add e get executam")
class MemoriaDoDesignerExecutaTest {

    /** Provider de teste registrado via SPI em {@code src/test/resources/META-INF/services}. */
    static final String BACKEND = "memoria-h2-teste";

    private static final JdbcDataSource BANCO = new JdbcDataSource();

    static {
        BANCO.setURL("jdbc:h2:mem:rchflw115;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    }

    @BeforeEach
    void tabelaDaMigracao() throws Exception {
        try (InputStream ddl = JdbcMemoryAdapter.class.getResourceAsStream(
                "/db/migration/V3_1__create_chat_memory.sql");
             Connection conn = BANCO.getConnection();
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

    /** Um nó Memória como o painel o grava: backend, Operação e, no add, o Papel da mensagem. */
    private static Map<String, Object> memoria(String id, Map<String, Object> config, String proximo) {
        Map<String, Object> node = new HashMap<>();
        node.put("id", id);
        node.put("type", "memory");
        node.put("componentId", "memory");
        Map<String, Object> cfg = new LinkedHashMap<>(config);
        cfg.put("memoryBackend", BACKEND);
        node.put("config", cfg);
        if (proximo != null) {
            node.put("connections", List.of(Map.of("sourceId", id, "targetId", proximo)));
        }
        return node;
    }

    @SafeVarargs
    private static FlowRun executar(String flowId, Object input, Map<String, Object>... passos) throws Exception {
        Map<String, Object> documento = new HashMap<>();
        documento.put("id", flowId);
        documento.put("metadata", Map.of("name", "Memória"));
        documento.put("steps", List.of(passos));
        Flow flow = new DefaultWorkflowDeserializer(new DefaultFlowStepFactory(new DefaultComponentCatalog(),
                mock(DynamicWorkflowService.class), null, null, null)).toFlow(documento);
        FlowEngine engine = FlowEngineFactory.create(new InMemoryFlowRepository(), null, null,
                new InMemoryStateManager());
        DefaultExecutionContext ctx = new DefaultExecutionContext("acme", "u1", flow.getId(),
                MessageWindowChatMemory.builder().maxMessages(5).build());
        ctx.set("input", input);
        FlowResult result = engine.execute(flow, ctx).get(15, TimeUnit.SECONDS);
        return new FlowRun(result, ctx);
    }

    private record FlowRun(FlowResult result, DefaultExecutionContext ctx) {
        Object saida(String passo) {
            return ctx.get(passo).orElse(null);
        }
    }

    @Test
    @DisplayName("add guarda a entrada com o papel escolhido, passa a entrada adiante, e o get devolve o histórico")
    void addDepoisGet() throws Exception {
        FlowRun run = executar("wf-memoria", Map.of("input", "quero trocar meu plano"),
                memoria("guarda", Map.of("operation", "add", "memoryRole", "user"), "le"),
                memoria("le", Map.of("operation", "get"), null));

        assertThat(run.result().getStatus())
                .as("antes: 'Input must be a ChatMessage' (e, sem o campo, 'diga a operação em operation')")
                .isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(run.saida("guarda")).as("o add não tem saída própria: o passo seguinte vê a mesma entrada")
                .isEqualTo(Map.of("input", "quero trocar meu plano"));
        assertThat(mensagens(run.saida("le")))
                .containsExactly("USER: quero trocar meu plano");
    }

    @Test
    @DisplayName("o histórico é do fluxo: outra execução lê o que a anterior guardou; clear apaga")
    void historicoEntreExecucoesEClear() throws Exception {
        executar("wf-conversa", "oi", memoria("guarda", Map.of("operation", "add", "memoryRole", "user"), null));
        FlowRun resposta = executar("wf-conversa", "olá, como posso ajudar?",
                memoria("guarda", Map.of("operation", "add", "memoryRole", "ai"), "le"),
                memoria("le", Map.of("operation", "get"), null));

        assertThat(resposta.result().getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(mensagens(resposta.saida("le"))).containsExactly("USER: oi", "AI: olá, como posso ajudar?");

        FlowRun limpa = executar("wf-conversa", "fim",
                memoria("limpa", Map.of("operation", "clear"), "le"),
                memoria("le", Map.of("operation", "get"), null));
        assertThat(limpa.result().getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(limpa.saida("limpa")).isEqualTo("fim");
        assertThat(limpa.saida("le")).isEqualTo(List.of());
    }

    @Test
    @DisplayName("sem Operação ou sem Papel no add, a falha diz o campo do painel a preencher")
    void semOsCampos() throws Exception {
        FlowRun semOperacao = executar("wf-sem-op", "oi", memoria("guarda", Map.of(), null));
        assertThat(semOperacao.result().getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(semOperacao.result().getErrors().toString())
                .contains("add, get ou clear")
                .contains("escolha a Operação no painel")
                .doesNotContain("o painel ainda não tem esse campo");

        FlowRun semPapel = executar("wf-sem-papel", "oi", memoria("guarda", Map.of("operation", "add"), null));
        assertThat(semPapel.result().getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(semPapel.result().getErrors().toString())
                .contains("Escolha o Papel da mensagem no painel (user, ai, system)")
                .doesNotContain("Input must be a ChatMessage");
    }

    /** "TIPO: texto" de cada mensagem do get, no formato canônico que a memória grava. */
    @SuppressWarnings("unchecked")
    private static List<String> mensagens(Object saida) {
        assertThat(saida).as("o get devolve a lista de mensagens, serializável").isInstanceOf(List.class);
        List<String> out = new ArrayList<>();
        for (Object item : (List<Object>) saida) {
            Map<String, Object> m = (Map<String, Object>) item;
            Object texto = m.containsKey("text") ? m.get("text")
                    : ((List<Map<String, Object>>) m.get("contents")).get(0).get("text");
            out.add(m.get("type") + ": " + texto);
        }
        return out;
    }

    /** O {@link JdbcMemoryAdapter} de verdade, com o DataSource do H2 que o servidor ainda não entrega ao nó. */
    public static final class MemoriaH2Factory implements LangChainAdapterFactory {

        @Override
        public String getProvider() {
            return BACKEND;
        }

        @Override
        public boolean supports(String type) {
            return "memory".equals(type);
        }

        @Override
        public LangChainAdapter createAdapter(Map<String, Object> properties) {
            Map<String, Object> props = new HashMap<>(properties);
            props.put("datasource", BANCO);
            return new JdbcMemoryAdapter.Factory().createAdapter(props);
        }
    }
}
