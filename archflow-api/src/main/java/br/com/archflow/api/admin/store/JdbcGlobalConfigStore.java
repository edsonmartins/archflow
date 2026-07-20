package br.com.archflow.api.admin.store;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * {@link GlobalConfigStore} durável sobre a tabela {@code global_config}
 * (migration {@code V6_4__create_global_config.sql}).
 *
 * <p>Upsert portável (PostgreSQL, H2): UPDATE primeiro e INSERT quando não
 * existe linha — mesmo padrão do {@code JdbcFlowRepository} e do
 * {@code JdbcWorkflowRuntimeStore}.
 */
public class JdbcGlobalConfigStore implements GlobalConfigStore {

    private final DataSource dataSource;

    public JdbcGlobalConfigStore(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource cannot be null");
        }
        this.dataSource = dataSource;
    }

    @Override
    public Optional<String> get(String key) {
        String sql = "SELECT config_value FROM global_config WHERE config_key = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read global config key: " + key, e);
        }
    }

    @Override
    public void put(String key, String value) {
        Timestamp now = Timestamp.from(Instant.now());
        String update = "UPDATE global_config SET config_value = ?, updated_at = ? WHERE config_key = ?";
        String insert = "INSERT INTO global_config (config_key, config_value, updated_at) VALUES (?, ?, ?)";
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(update)) {
                ps.setString(1, value);
                ps.setTimestamp(2, now);
                ps.setString(3, key);
                if (ps.executeUpdate() > 0) {
                    return;
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(insert)) {
                ps.setString(1, key);
                ps.setString(2, value);
                ps.setTimestamp(3, now);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to write global config key: " + key, e);
        }
    }
}
