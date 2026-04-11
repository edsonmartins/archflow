package br.com.archflow.engine.persistence.jdbc;

import br.com.archflow.model.flow.AuditLog;
import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.flow.FlowStatus;
import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the JdbcStateRepository against a real H2 in-memory database.
 *
 * <p>The production code uses {@code ?::json} for PostgreSQL JSON casts.
 * H2 in PostgreSQL compatibility mode accepts this syntax, so we use
 * {@code MODE=PostgreSQL} on the JDBC URL.
 */
@DisplayName("JdbcStateRepository")
class JdbcStateRepositoryTest {

    private static javax.sql.DataSource dataSource;
    private JdbcStateRepository repo;

    @BeforeAll
    static void createDataSource() throws SQLException {
        // H2 in PostgreSQL compatibility mode — accepts ?::json syntax
        String url = "jdbc:h2:mem:testdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL(url);
        ds.setUser("sa");
        ds.setPassword("");
        dataSource = ds;

        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS flow_states (
                    tenant_id       VARCHAR(255) NOT NULL,
                    flow_id         VARCHAR(255) NOT NULL,
                    status          VARCHAR(50),
                    current_step_id VARCHAR(255),
                    variables       TEXT,
                    metrics         TEXT,
                    error           TEXT,
                    updated_at      TIMESTAMP,
                    PRIMARY KEY (tenant_id, flow_id)
                )
            """);
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS audit_logs (
                    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                    tenant_id       VARCHAR(255) NOT NULL,
                    flow_id         VARCHAR(255) NOT NULL,
                    state_snapshot  TEXT,
                    created_at      TIMESTAMP
                )
            """);
        }
    }

    @BeforeEach
    void setUp() throws SQLException {
        repo = new JdbcStateRepository(dataSource);
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("DELETE FROM flow_states");
            conn.createStatement().execute("DELETE FROM audit_logs");
        }
    }

    /**
     * Helper: inserts directly via SQL to bypass the PG-specific upsert.
     */
    private void insertState(String tenantId, String flowId, String status, String stepId) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            var ps = conn.prepareStatement(
                    "INSERT INTO flow_states (tenant_id, flow_id, status, current_step_id, updated_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)");
            ps.setString(1, tenantId);
            ps.setString(2, flowId);
            ps.setString(3, status);
            ps.setString(4, stepId);
            ps.executeUpdate();
        }
    }

    @Test
    void getStateRoundTrip() throws SQLException {
        insertState("acme", "flow-1", "RUNNING", "step-2");
        FlowState loaded = repo.getState("acme", "flow-1");

        assertThat(loaded).isNotNull();
        assertThat(loaded.getTenantId()).isEqualTo("acme");
        assertThat(loaded.getFlowId()).isEqualTo("flow-1");
        assertThat(loaded.getStatus()).isEqualTo(FlowStatus.RUNNING);
        assertThat(loaded.getCurrentStepId()).isEqualTo("step-2");
    }

    @Test
    void getStateReturnsNullForUnknown() {
        assertThat(repo.getState("acme", "ghost")).isNull();
    }

    @Test
    void tenantIsolation() throws SQLException {
        insertState("tenantA", "f1", "RUNNING", null);
        insertState("tenantB", "f1", "COMPLETED", null);

        assertThat(repo.getState("tenantA", "f1").getStatus()).isEqualTo(FlowStatus.RUNNING);
        assertThat(repo.getState("tenantB", "f1").getStatus()).isEqualTo(FlowStatus.COMPLETED);
    }

    @Test
    void getStatesByTenant() throws SQLException {
        insertState("acme", "f1", "RUNNING", null);
        insertState("acme", "f2", "COMPLETED", null);
        insertState("other", "f3", "RUNNING", null);

        List<FlowState> acmeStates = repo.getStatesByTenant("acme");
        assertThat(acmeStates).hasSize(2);
        assertThat(acmeStates).extracting(FlowState::getTenantId).containsOnly("acme");
    }

    @Test
    void getStatesByTenantReturnsEmptyForUnknown() {
        assertThat(repo.getStatesByTenant("ghost")).isEmpty();
    }

    @Test
    void getStateWithDefaultTenant() throws SQLException {
        insertState("SYSTEM", "f1", "INITIALIZED", null);
        FlowState loaded = repo.getState("f1");
        assertThat(loaded).isNotNull();
        assertThat(loaded.getTenantId()).isEqualTo("SYSTEM");
    }

    @Test
    void saveAuditLog() {
        AuditLog log = AuditLog.builder()
                .flowId("f1")
                .timestamp(Instant.now())
                .stepId("s1")
                .build();
        repo.saveAuditLog("acme", "f1", log);
    }

    @Test
    void saveAuditLogWithDefaultTenant() {
        AuditLog log = AuditLog.builder()
                .flowId("f1")
                .timestamp(Instant.now())
                .build();
        repo.saveAuditLog("f1", log);
    }
}
