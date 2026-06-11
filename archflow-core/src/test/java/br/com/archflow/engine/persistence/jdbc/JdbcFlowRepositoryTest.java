package br.com.archflow.engine.persistence.jdbc;

import br.com.archflow.model.config.FlowConfiguration;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowMetadata;
import br.com.archflow.model.flow.FlowStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests JdbcFlowRepository against H2 in PostgreSQL compatibility mode
 * (accepts the {@code ?::json} casts used by the production SQL).
 */
@DisplayName("JdbcFlowRepository")
class JdbcFlowRepositoryTest {

    private static javax.sql.DataSource dataSource;
    private JdbcFlowRepository repo;

    /** Codec for a minimal flow representation: {"id": "..."} round-trip. */
    private static final FlowJsonCodec STUB_CODEC = new FlowJsonCodec() {
        private final ObjectMapper mapper = new ObjectMapper();

        @Override
        public String toJson(Flow flow) throws Exception {
            return mapper.writeValueAsString(Map.of("id", flow.getId()));
        }

        @Override
        public Flow fromJson(String json) throws Exception {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = mapper.readValue(json, Map.class);
            return stubFlow((String) map.get("id"));
        }
    };

    @BeforeAll
    static void createDataSource() throws SQLException {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:flowrepo;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        dataSource = ds;

        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS flows (
                    id          VARCHAR(64)  PRIMARY KEY,
                    definition  TEXT         NOT NULL,
                    updated_at  TIMESTAMP    NOT NULL
                )
            """);
        }
    }

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("DELETE FROM flows");
        }
        repo = new JdbcFlowRepository(dataSource, STUB_CODEC);
    }

    @Test
    @DisplayName("save + findById round-trip")
    void saveAndFind() {
        repo.save(stubFlow("flow-1"));

        Optional<Flow> found = repo.findById("flow-1");
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo("flow-1");
    }

    @Test
    @DisplayName("findById returns empty for unknown id")
    void findUnknown() {
        assertThat(repo.findById("ghost")).isEmpty();
    }

    @Test
    @DisplayName("save again upserts the existing row")
    void saveUpserts() {
        repo.save(stubFlow("flow-1"));
        repo.save(stubFlow("flow-1"));

        assertThat(repo.findById("flow-1")).isPresent();
    }

    @Test
    @DisplayName("delete removes the flow")
    void delete() {
        repo.save(stubFlow("flow-1"));
        repo.delete("flow-1");
        assertThat(repo.findById("flow-1")).isEmpty();
    }

    @Test
    @DisplayName("delete on unknown id is safe")
    void deleteUnknown() {
        repo.delete("ghost");
    }

    @Test
    @DisplayName("constructor rejects null dependencies")
    void constructorValidation() {
        assertThatThrownBy(() -> new JdbcFlowRepository(null, STUB_CODEC))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JdbcFlowRepository(dataSource, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Flow stubFlow(String id) {
        return new Flow() {
            @Override public String getId() { return id; }
            @Override public FlowMetadata getMetadata() { return null; }
            @Override public List<FlowStep> getSteps() { return List.of(); }
            @Override public FlowConfiguration getConfiguration() { return null; }
            @Override public void validate() {}
        };
    }
}
