package br.com.archflow.engine.persistence.jdbc;

import br.com.archflow.model.flow.AuditLog;
import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.flow.FlowStatus;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integração com PostgreSQL REAL via Testcontainers — exercita o caminho de
 * escrita do JdbcStateRepository (ON CONFLICT ... DO UPDATE e casts ?::json),
 * que é específico de Postgres e não roda no H2 dos testes unitários.
 * A auditoria apontou exatamente isso: saveState nunca era exercitado.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("JdbcStateRepository (PostgreSQL real)")
class JdbcStateRepositoryPostgresTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static DataSource dataSource;
    private JdbcStateRepository repo;

    @BeforeAll
    static void createSchema() throws SQLException {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        dataSource = ds;

        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS flow_states (
                    tenant_id       VARCHAR(36)  NOT NULL,
                    flow_id         VARCHAR(36)  NOT NULL,
                    status          VARCHAR(32)  NOT NULL,
                    current_step_id VARCHAR(64),
                    variables       JSON,
                    metrics         JSON,
                    error           JSON,
                    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (tenant_id, flow_id)
                )
            """);
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS audit_logs (
                    id              BIGSERIAL    PRIMARY KEY,
                    tenant_id       VARCHAR(36)  NOT NULL,
                    flow_id         VARCHAR(36)  NOT NULL,
                    state_snapshot  JSON,
                    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
            """);
        }
    }

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("DELETE FROM flow_states");
            conn.createStatement().execute("DELETE FROM audit_logs");
        }
        repo = new JdbcStateRepository(dataSource);
    }

    @Test
    @DisplayName("saveState + getState round-trip (INSERT via ON CONFLICT)")
    void saveStateRoundTrip() {
        FlowState state = FlowState.builder()
                .tenantId("acme")
                .flowId("flow-1")
                .status(FlowStatus.RUNNING)
                .currentStepId("step-2")
                .variables(Map.of("k", "v"))
                .build();

        repo.saveState("acme", "flow-1", state);

        FlowState loaded = repo.getState("acme", "flow-1");
        assertThat(loaded).isNotNull();
        assertThat(loaded.getStatus()).isEqualTo(FlowStatus.RUNNING);
        assertThat(loaded.getCurrentStepId()).isEqualTo("step-2");
        assertThat(loaded.getVariables()).containsEntry("k", "v");
    }

    @Test
    @DisplayName("saveState duas vezes faz upsert (ON CONFLICT DO UPDATE)")
    void saveStateUpserts() {
        FlowState running = FlowState.builder()
                .tenantId("acme").flowId("flow-1")
                .status(FlowStatus.RUNNING).build();
        FlowState completed = FlowState.builder()
                .tenantId("acme").flowId("flow-1")
                .status(FlowStatus.COMPLETED).build();

        repo.saveState("acme", "flow-1", running);
        repo.saveState("acme", "flow-1", completed);

        assertThat(repo.getState("acme", "flow-1").getStatus())
                .isEqualTo(FlowStatus.COMPLETED);
        assertThat(repo.getStatesByTenant("acme")).hasSize(1);
    }

    @Test
    @DisplayName("estado é isolado por tenant")
    void tenantIsolation() {
        repo.saveState("tenantA", "f1", FlowState.builder()
                .tenantId("tenantA").flowId("f1").status(FlowStatus.RUNNING).build());
        repo.saveState("tenantB", "f1", FlowState.builder()
                .tenantId("tenantB").flowId("f1").status(FlowStatus.COMPLETED).build());

        assertThat(repo.getState("tenantA", "f1").getStatus()).isEqualTo(FlowStatus.RUNNING);
        assertThat(repo.getState("tenantB", "f1").getStatus()).isEqualTo(FlowStatus.COMPLETED);
    }

    @Test
    @DisplayName("audit log persiste com snapshot JSON")
    void auditLogPersists() {
        AuditLog log = AuditLog.builder()
                .flowId("flow-1")
                .build();

        repo.saveAuditLog("acme", "flow-1", log);
        // sem exceção = INSERT com ?::json aceito pelo Postgres
    }

    @Test
    @DisplayName("estado sobrevive a uma nova instância do repositório (restart)")
    void stateSurvivesRepositoryRestart() {
        repo.saveState("acme", "flow-9", FlowState.builder()
                .tenantId("acme").flowId("flow-9").status(FlowStatus.PAUSED).build());

        JdbcStateRepository fresh = new JdbcStateRepository(dataSource);
        assertThat(fresh.getState("acme", "flow-9").getStatus()).isEqualTo(FlowStatus.PAUSED);
    }

    @Test
    @DisplayName("JdbcFlowRepository: round-trip no Postgres")
    void flowRepositoryOnPostgres() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS flows (
                    id          VARCHAR(64)  PRIMARY KEY,
                    definition  TEXT         NOT NULL,
                    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
            """);
        }
        FlowJsonCodec codec = new FlowJsonCodec() {
            @Override public String toJson(br.com.archflow.model.flow.Flow flow) {
                return "{\"id\":\"" + flow.getId() + "\"}";
            }
            @Override public br.com.archflow.model.flow.Flow fromJson(String json) {
                return stubFlow(json.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
            }
        };

        JdbcFlowRepository flowRepo = new JdbcFlowRepository(dataSource, codec);
        flowRepo.save(stubFlow("pg-flow"));

        assertThat(flowRepo.findById("pg-flow")).isPresent();
        flowRepo.save(stubFlow("pg-flow")); // upsert: caminho UPDATE
        flowRepo.delete("pg-flow");
        assertThat(flowRepo.findById("pg-flow")).isEmpty();
    }

    private static br.com.archflow.model.flow.Flow stubFlow(String id) {
        return new br.com.archflow.model.flow.Flow() {
            @Override public String getId() { return id; }
            @Override public br.com.archflow.model.flow.FlowMetadata getMetadata() { return null; }
            @Override public List<br.com.archflow.model.flow.FlowStep> getSteps() { return List.of(); }
            @Override public br.com.archflow.model.config.FlowConfiguration getConfiguration() { return null; }
            @Override public void validate() {}
        };
    }
}
