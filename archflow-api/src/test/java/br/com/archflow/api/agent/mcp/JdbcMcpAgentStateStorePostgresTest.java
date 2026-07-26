package br.com.archflow.api.agent.mcp;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O store durável é o que faz a suspensão valer: em memória, um laço suspenso
 * morre no restart — o oposto do motivo de suspender. Exercita o caminho de
 * escrita específico de Postgres ({@code ON CONFLICT}, cast {@code ?::json}), que
 * o H2 não representa fielmente.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("JdbcMcpAgentStateStore (PostgreSQL real)")
class JdbcMcpAgentStateStorePostgresTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static DataSource dataSource;
    private JdbcMcpAgentStateStore store;

    @BeforeAll
    static void createSchema() throws SQLException {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        dataSource = ds;
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS mcp_agent_states (
                    run_id       VARCHAR(64)  PRIMARY KEY,
                    tenant_id    VARCHAR(36)  NOT NULL,
                    request_id   VARCHAR(64),
                    state        JSON         NOT NULL,
                    suspended_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
            """);
        }
    }

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("DELETE FROM mcp_agent_states");
        }
        store = new JdbcMcpAgentStateStore(dataSource);
    }

    private static McpAgentState state(String runId, String tenant, String requestId, Instant at) {
        return new McpAgentState(runId, tenant, "prompt de sistema", "NONCE1",
                List.of("{\"type\":\"USER\",\"contents\":[{\"type\":\"TEXT\",\"text\":\"oi\"}]}"),
                List.of(new McpAgentState.SerializedToolCall(
                        "ler_logs", Map.of("servico", "traefik"), "linha", false, ToolTrust.UNTRUSTED)),
                3, "ultimo texto",
                new McpAgentState.PendingApproval(requestId, "reiniciar_servico",
                        Map.of("servico", "traefik"), "call-1"),
                at);
    }

    @Test
    @DisplayName("round-trip completo: tudo que a retomada precisa volta intacto")
    void roundTrip() {
        store.save(state("run-1", "acme", "req-1", Instant.parse("2026-07-26T10:00:00Z")));

        McpAgentState loaded = store.findByRunId("run-1").orElseThrow();

        assertThat(loaded.tenantId()).isEqualTo("acme");
        assertThat(loaded.fenceNonce())
                .as("nonce novo faria a cerca deixar de casar com a regra do prompt")
                .isEqualTo("NONCE1");
        assertThat(loaded.iteration())
                .as("sem a iteracao, retomadas sucessivas escapariam do maxIterations")
                .isEqualTo(3);
        assertThat(loaded.messages()).hasSize(1);
        assertThat(loaded.toolCalls()).singleElement().satisfies(c -> {
            assertThat(c.name()).isEqualTo("ler_logs");
            assertThat(c.trust()).isEqualTo(ToolTrust.UNTRUSTED);
        });
        assertThat(loaded.pending().toolName()).isEqualTo("reiniciar_servico");
        assertThat(loaded.pending().arguments()).containsEntry("servico", "traefik");
    }

    @Test
    @DisplayName("busca pelo requestId — é por ele que o decisor chega")
    void findsByRequestId() {
        store.save(state("run-1", "acme", "req-1", Instant.now()));

        assertThat(store.findByRequestId("req-1")).isPresent();
        assertThat(store.findByRequestId("desconhecido")).isEmpty();
    }

    @Test
    @DisplayName("salvar duas vezes faz upsert (ON CONFLICT)")
    void saveUpserts() {
        store.save(state("run-1", "acme", "req-1", Instant.now()));
        store.save(state("run-1", "acme", "req-2", Instant.now()));

        assertThat(store.findByRequestId("req-1")).isEmpty();
        assertThat(store.findByRequestId("req-2")).isPresent();
        assertThat(store.findPendingByTenant("acme")).hasSize(1);
    }

    @Test
    @DisplayName("a fila por tenant é isolada e ordenada pelo mais antigo")
    void pendingQueueIsTenantScopedAndOrdered() {
        Instant t0 = Instant.parse("2026-07-26T10:00:00Z");
        store.save(state("run-2", "acme", "req-2", t0.plusSeconds(60)));
        store.save(state("run-1", "acme", "req-1", t0));
        store.save(state("run-3", "beta", "req-3", t0.plusSeconds(120)));

        assertThat(store.findPendingByTenant("acme"))
                .extracting(McpAgentState::runId)
                .as("quem espera ha mais tempo vem primeiro")
                .containsExactly("run-1", "run-2");
        assertThat(store.findPendingByTenant("beta")).extracting(McpAgentState::runId)
                .containsExactly("run-3");
        assertThat(store.findPendingByTenant("all")).hasSize(3);
    }

    @Test
    @DisplayName("delete consome o estado — a decisão não pode ser aplicada duas vezes")
    void deleteConsumesTheState() {
        store.save(state("run-1", "acme", "req-1", Instant.now()));

        assertThat(store.delete("run-1")).isTrue();
        assertThat(store.delete("run-1")).isFalse();
        assertThat(store.findByRequestId("req-1")).isEmpty();
    }

    @Test
    @DisplayName("estado sobrevive a uma instância nova do store (restart)")
    void survivesStoreRestart() {
        store.save(state("run-9", "acme", "req-9", Instant.now()));

        JdbcMcpAgentStateStore fresh = new JdbcMcpAgentStateStore(dataSource);

        assertThat(fresh.findByRequestId("req-9"))
                .as("e este o ponto todo: o laco tem de sobreviver ao processo")
                .isPresent();
    }
}
