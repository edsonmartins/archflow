package br.com.archflow.api.flow;

import br.com.archflow.agent.persistence.InMemoryFlowRepository;
import br.com.archflow.api.flow.adapter.AdapterConnections;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.plugin.api.catalog.DefaultComponentCatalog;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * RCHFLW-119 — o nó Memória com o backend {@code jdbc}, montado com as chaves do designer, executa no motor de verdade
 * contra PostgreSQL real, com a tabela criada pelas migrações do Flyway como estão no repositório.
 *
 * <p>Antes o nó falhava sempre com "a memória JDBC precisa de um DataSource": o servidor não entregava o banco ao
 * adapter, e pelo editor só o redis funcionava. Agora a fábrica de passos recebe o banco da persistência JDBC pelo
 * mesmo caminho das conexões dos outros adapters ({@link DefaultFlowStepFactory#setServerConnections}) — só com
 * {@code archflow.persistence.jdbc.enabled=true}. O teste confere também que o tenant e a sessão da execução são os
 * que chegam à tabela, e que sem a persistência a falha diz o que ligar, sem gravar em lugar nenhum.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("nó Memória jdbc num fluxo: motor real, PostgreSQL real, migrações do Flyway")
class MemoriaJdbcDoFluxoPostgresTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static PGSimpleDataSource dataSource;

    @BeforeAll
    static void migrar() {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }

    /** Um nó Memória como o painel o grava: backend, Operação e, no add, o Papel da mensagem. */
    private static Map<String, Object> memoria(String id, Map<String, Object> config, String proximo) {
        Map<String, Object> node = new HashMap<>();
        node.put("id", id);
        node.put("type", "memory");
        node.put("componentId", "memory");
        Map<String, Object> cfg = new LinkedHashMap<>(config);
        cfg.put("memoryBackend", "jdbc");
        node.put("config", cfg);
        if (proximo != null) {
            // As arestas moram em steps[].connections — é onde o motor as lê.
            node.put("connections", List.of(Map.of("sourceId", id, "targetId", proximo)));
        }
        return node;
    }

    @SafeVarargs
    private static FlowRun executar(boolean persistenciaJdbc, String tenant, String sessao, Object input,
                                    Map<String, Object>... passos) throws Exception {
        Map<String, Object> documento = new HashMap<>();
        documento.put("id", "wf-" + UUID.randomUUID());
        documento.put("metadata", Map.of("name", "Memória JDBC"));
        documento.put("steps", List.of(passos));
        DefaultFlowStepFactory factory = new DefaultFlowStepFactory(new DefaultComponentCatalog(),
                mock(DynamicWorkflowService.class), null, null, null);
        MockEnvironment env = new MockEnvironment();
        if (persistenciaJdbc) {
            env.setProperty(AdapterConnections.JDBC_PERSISTENCE_PROPERTY, "true");
        }
        // O banco existe nos dois casos: sem a propriedade, ele não pode virar o banco da memória dos fluxos.
        factory.setServerConnections(env, MemoriaDoDesignerExecutaTest.banco(dataSource));
        Flow flow = new DefaultWorkflowDeserializer(factory).toFlow(documento);
        FlowEngine engine = FlowEngineFactory.create(new InMemoryFlowRepository(), null, null,
                new InMemoryStateManager());
        DefaultExecutionContext ctx = new DefaultExecutionContext(tenant, "u1", sessao,
                MessageWindowChatMemory.builder().maxMessages(5).build());
        ctx.set("input", input);
        FlowResult result = engine.execute(flow, ctx).get(30, TimeUnit.SECONDS);
        return new FlowRun(result, ctx);
    }

    private record FlowRun(FlowResult result, DefaultExecutionContext ctx) {
        Object saida(String passo) {
            return ctx.get(passo).orElse(null);
        }
    }

    private static String tenant() {
        return "t-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("add e depois get no mesmo fluxo: a mensagem vai para chat_messages com o tenant e a sessão")
    void addDepoisGet() throws Exception {
        String tenant = tenant();
        String sessao = "sessao-" + UUID.randomUUID();

        FlowRun run = executar(true, tenant, sessao, Map.of("input", "quero trocar meu plano"),
                memoria("guarda", Map.of("operation", "add", "memoryRole", "user"), "le"),
                memoria("le", Map.of("operation", "get"), null));

        assertThat(run.result().getStatus())
                .as("antes: 'a memória JDBC precisa de um DataSource do servidor' — %s", run.result().getErrors())
                .isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(mensagens(run.saida("le"))).containsExactly("USER: quero trocar meu plano");
        assertThat(linhas(tenant, sessao)).as("a linha é do tenant e da sessão da execução").isEqualTo(1);
    }

    @Test
    @DisplayName("o histórico é do tenant e da sessão: outra execução da mesma sessão lê, outra sessão ou tenant não")
    void historicoIsoladoPorTenantESessao() throws Exception {
        String tenant = tenant();
        String outroTenant = tenant();
        String sessao = "sessao-" + UUID.randomUUID();

        executar(true, tenant, sessao, "oi",
                memoria("guarda", Map.of("operation", "add", "memoryRole", "user"), null));
        FlowRun resposta = executar(true, tenant, sessao, "olá, como posso ajudar?",
                memoria("guarda", Map.of("operation", "add", "memoryRole", "ai", "memoryMaxMessages", 10), "le"),
                memoria("le", Map.of("operation", "get"), null));

        assertThat(resposta.result().getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(mensagens(resposta.saida("le")))
                .as("da mais antiga para a mais nova")
                .containsExactly("USER: oi", "AI: olá, como posso ajudar?");

        FlowRun outroTenantMesmaSessao = executar(true, outroTenant, sessao, "x",
                memoria("le", Map.of("operation", "get"), null));
        assertThat(outroTenantMesmaSessao.result().getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(outroTenantMesmaSessao.saida("le")).isEqualTo(List.of());

        FlowRun outraSessao = executar(true, tenant, "sessao-" + UUID.randomUUID(), "x",
                memoria("le", Map.of("operation", "get"), null));
        assertThat(outraSessao.saida("le")).isEqualTo(List.of());

        FlowRun limpa = executar(true, tenant, sessao, "fim",
                memoria("limpa", Map.of("operation", "clear"), "le"),
                memoria("le", Map.of("operation", "get"), null));
        assertThat(limpa.result().getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(limpa.saida("le")).isEqualTo(List.of());
        assertThat(linhas(tenant, sessao)).isZero();
    }

    @Test
    @DisplayName("um tenant com mais de 36 caracteres grava e lê: a coluna tem a largura do tenant da execução")
    void tenantLongo() throws Exception {
        // 60 caracteres: cabe nas tabelas do tenant da execução (até 128), não cabia no VARCHAR(36) da V3_1.
        String tenant = "cliente-com-um-identificador-bem-longo-" + UUID.randomUUID().toString().substring(0, 21);
        String sessao = "sessao-" + UUID.randomUUID();

        FlowRun run = executar(true, tenant, sessao, "oi",
                memoria("guarda", Map.of("operation", "add", "memoryRole", "user"), "le"),
                memoria("le", Map.of("operation", "get"), null));

        assertThat(run.result().getStatus())
                .as("antes da V3_2: 'value too long for type character varying(36)' — %s", run.result().getErrors())
                .isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(mensagens(run.saida("le"))).containsExactly("USER: oi");
        assertThat(linhas(tenant, sessao)).isEqualTo(1);
    }

    @Test
    @DisplayName("sem a persistência JDBC do servidor, o nó falha dizendo o que ligar e não grava nada")
    void semPersistenciaJdbc() throws Exception {
        String tenant = tenant();
        String sessao = "sessao-" + UUID.randomUUID();

        FlowRun run = executar(false, tenant, sessao, "oi",
                memoria("guarda", Map.of("operation", "add", "memoryRole", "user"), null));

        assertThat(run.result().getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(run.result().getErrors().toString())
                .contains("persistência JDBC do servidor não está ligada")
                .contains("archflow.persistence.jdbc.enabled=true");
        assertThat(linhas(tenant, sessao)).isZero();
    }

    private static int linhas(String tenant, String sessao) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM chat_messages WHERE tenant_id = ? AND session_id = ?")) {
            ps.setString(1, tenant);
            ps.setString(2, sessao);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
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
}
