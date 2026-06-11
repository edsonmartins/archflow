package br.com.archflow.conversation.persistence.jdbc;

import br.com.archflow.conversation.prompt.PromptRegistry;
import br.com.archflow.conversation.prompt.PromptVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Implementação JDBC do {@link PromptRegistry} — versões de prompt persistidas
 * na tabela {@code prompt_versions} (ver migration
 * {@code V001__create_conversations.sql}).
 *
 * <p>O número da versão é atribuído via {@code MAX(version) + 1} dentro de uma
 * transação; a PK composta {@code (tenant_id, prompt_id, version)} garante que
 * uma corrida entre instâncias falhe alto em vez de duplicar — o caller pode
 * simplesmente repetir o registro.
 */
public class JdbcPromptRegistry implements PromptRegistry {

    private static final Logger log = LoggerFactory.getLogger(JdbcPromptRegistry.class);

    private final DataSource dataSource;

    public JdbcPromptRegistry(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource cannot be null");
        }
        this.dataSource = dataSource;
    }

    @Override
    public PromptVersion register(String tenantId, String promptId, String template) {
        try (Connection conn = dataSource.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                int nextVersion;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COALESCE(MAX(version), 0) FROM prompt_versions WHERE tenant_id = ? AND prompt_id = ?")) {
                    ps.setString(1, tenantId);
                    ps.setString(2, promptId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        nextVersion = rs.getInt(1) + 1;
                    }
                }

                boolean active = nextVersion == 1; // primeira versão ativa automaticamente
                PromptVersion version = new PromptVersion(
                        promptId, tenantId, nextVersion, template, active, Instant.now());

                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO prompt_versions (tenant_id, prompt_id, version, template, active, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)) {
                    ps.setString(1, tenantId);
                    ps.setString(2, promptId);
                    ps.setInt(3, nextVersion);
                    ps.setString(4, template);
                    ps.setBoolean(5, active);
                    ps.setTimestamp(6, Timestamp.from(version.createdAt()));
                    ps.executeUpdate();
                }

                conn.commit();
                return version;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }

        } catch (SQLException e) {
            log.error("Failed to register prompt {} (tenant {})", promptId, tenantId, e);
            throw new RuntimeException("Failed to register prompt " + promptId, e);
        }
    }

    @Override
    public Optional<PromptVersion> getActive(String tenantId, String promptId) {
        return querySingle(
                "SELECT * FROM prompt_versions WHERE tenant_id = ? AND prompt_id = ? AND active = TRUE",
                ps -> {
                    ps.setString(1, tenantId);
                    ps.setString(2, promptId);
                });
    }

    @Override
    public Optional<PromptVersion> getVersion(String tenantId, String promptId, int version) {
        return querySingle(
                "SELECT * FROM prompt_versions WHERE tenant_id = ? AND prompt_id = ? AND version = ?",
                ps -> {
                    ps.setString(1, tenantId);
                    ps.setString(2, promptId);
                    ps.setInt(3, version);
                });
    }

    @Override
    public List<PromptVersion> listVersions(String tenantId, String promptId) {
        List<PromptVersion> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM prompt_versions WHERE tenant_id = ? AND prompt_id = ? ORDER BY version DESC")) {

            ps.setString(1, tenantId);
            ps.setString(2, promptId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapVersion(rs));
                }
            }
            return results;

        } catch (SQLException e) {
            log.error("Failed to list versions of prompt {} (tenant {})", promptId, tenantId, e);
            throw new RuntimeException("Failed to list versions of " + promptId, e);
        }
    }

    @Override
    public void activateVersion(String tenantId, String promptId, int version) {
        try (Connection conn = dataSource.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                int activated;
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE prompt_versions SET active = TRUE WHERE tenant_id = ? AND prompt_id = ? AND version = ?")) {
                    ps.setString(1, tenantId);
                    ps.setString(2, promptId);
                    ps.setInt(3, version);
                    activated = ps.executeUpdate();
                }
                if (activated == 0) {
                    conn.rollback();
                    throw new IllegalArgumentException(
                            "Prompt version not found: " + promptId + " v" + version + " (tenant " + tenantId + ")");
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE prompt_versions SET active = FALSE WHERE tenant_id = ? AND prompt_id = ? AND version <> ?")) {
                    ps.setString(1, tenantId);
                    ps.setString(2, promptId);
                    ps.setInt(3, version);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }

        } catch (SQLException e) {
            log.error("Failed to activate prompt {} v{} (tenant {})", promptId, version, tenantId, e);
            throw new RuntimeException("Failed to activate prompt " + promptId + " v" + version, e);
        }
    }

    @Override
    public List<String> listPromptIds(String tenantId) {
        List<String> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT DISTINCT prompt_id FROM prompt_versions WHERE tenant_id = ? ORDER BY prompt_id")) {

            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(rs.getString(1));
                }
            }
            return results;

        } catch (SQLException e) {
            log.error("Failed to list prompt ids (tenant {})", tenantId, e);
            throw new RuntimeException("Failed to list prompt ids", e);
        }
    }

    // ── helpers ─────────────────────────────────────────────────────

    private Optional<PromptVersion> querySingle(String sql, JdbcSupport.ParameterBinder binder) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapVersion(rs)) : Optional.empty();
            }

        } catch (SQLException e) {
            log.error("Failed to query prompt version", e);
            throw new RuntimeException("Failed to query prompt version", e);
        }
    }

    private PromptVersion mapVersion(ResultSet rs) throws SQLException {
        return new PromptVersion(
                rs.getString("prompt_id"),
                rs.getString("tenant_id"),
                rs.getInt("version"),
                rs.getString("template"),
                rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant());
    }
}
