package br.com.archflow.security.auth;

import br.com.archflow.model.security.Role;
import br.com.archflow.model.security.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Implementação JDBC do {@link UserRepository} — usuários persistidos na tabela
 * {@code users} e seus papéis na tabela {@code user_roles} (ver migration
 * {@code V001__create_security.sql}).
 *
 * <p>SQL ANSI portável (PostgreSQL, H2, MySQL). Os papéis são armazenados pelo
 * nome ({@link Role#getName()}) e reidratados a partir das definições built-in
 * ({@code ADMIN/DESIGNER/EXECUTOR/VIEWER}), mantendo as permissões coerentes com
 * o RBAC do sistema sem uma tabela de permissões separada.
 *
 * <p>Nunca registra o {@code passwordHash} em log — apenas o id/username.
 */
public class JdbcUserRepository implements UserRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcUserRepository.class);

    private final DataSource dataSource;

    public JdbcUserRepository(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource cannot be null");
        }
        this.dataSource = dataSource;
    }

    @Override
    public User save(User user) {
        if (user.getId() == null) {
            user.setId("user-" + UUID.randomUUID());
            user.setCreatedAt(LocalDateTime.now());
        }
        user.setUpdatedAt(LocalDateTime.now());

        String updateSql = """
            UPDATE users SET username = ?, email = ?, password_hash = ?, first_name = ?,
                   last_name = ?, enabled = ?, account_non_expired = ?, account_non_locked = ?,
                   credentials_non_expired = ?, updated_at = ?, last_login_at = ?
            WHERE id = ?
            """;
        String insertSql = """
            INSERT INTO users (id, username, email, password_hash, first_name, last_name,
                               enabled, account_non_expired, account_non_locked,
                               credentials_non_expired, created_at, updated_at, last_login_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (Connection conn = dataSource.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                int updated;
                try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                    ps.setString(1, user.getUsername());
                    ps.setString(2, user.getEmail());
                    ps.setString(3, user.getPasswordHash());
                    ps.setString(4, user.getFirstName());
                    ps.setString(5, user.getLastName());
                    ps.setBoolean(6, user.isEnabled());
                    ps.setBoolean(7, user.isAccountNonExpired());
                    ps.setBoolean(8, user.isAccountNonLocked());
                    ps.setBoolean(9, user.isCredentialsNonExpired());
                    ps.setTimestamp(10, timestamp(user.getUpdatedAt()));
                    ps.setTimestamp(11, timestamp(user.getLastLoginAt()));
                    ps.setString(12, user.getId());
                    updated = ps.executeUpdate();
                }
                if (updated == 0) {
                    try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                        ps.setString(1, user.getId());
                        ps.setString(2, user.getUsername());
                        ps.setString(3, user.getEmail());
                        ps.setString(4, user.getPasswordHash());
                        ps.setString(5, user.getFirstName());
                        ps.setString(6, user.getLastName());
                        ps.setBoolean(7, user.isEnabled());
                        ps.setBoolean(8, user.isAccountNonExpired());
                        ps.setBoolean(9, user.isAccountNonLocked());
                        ps.setBoolean(10, user.isCredentialsNonExpired());
                        ps.setTimestamp(11, timestamp(user.getCreatedAt()));
                        ps.setTimestamp(12, timestamp(user.getUpdatedAt()));
                        ps.setTimestamp(13, timestamp(user.getLastLoginAt()));
                        ps.executeUpdate();
                    }
                }
                replaceRoles(conn, user);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
            return user;

        } catch (SQLException e) {
            log.error("Failed to save user {} (username {})", user.getId(), user.getUsername(), e);
            throw new RuntimeException("Failed to save user " + user.getId(), e);
        }
    }

    private void replaceRoles(Connection conn, User user) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM user_roles WHERE user_id = ?")) {
            ps.setString(1, user.getId());
            ps.executeUpdate();
        }
        if (user.getRoles().isEmpty()) {
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO user_roles (user_id, role_name) VALUES (?, ?)")) {
            for (Role role : user.getRoles()) {
                ps.setString(1, user.getId());
                ps.setString(2, role.getName());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return findBy("username", username);
    }

    @Override
    public Optional<User> findById(String id) {
        return findBy("id", id);
    }

    private Optional<User> findBy(String column, String value) {
        String sql = "SELECT * FROM users WHERE " + column + " = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                User user = mapUser(rs);
                user.setRoles(loadRoles(conn, user.getId()));
                return Optional.of(user);
            }

        } catch (SQLException e) {
            log.error("Failed to load user by {} = {}", column, value, e);
            throw new RuntimeException("Failed to load user by " + column, e);
        }
    }

    private Set<Role> loadRoles(Connection conn, String userId) throws SQLException {
        Set<Role> roles = new LinkedHashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT role_name FROM user_roles WHERE user_id = ?")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    roles.add(rehydrateRole(rs.getString("role_name")));
                }
            }
        }
        return roles;
    }

    @Override
    public void delete(User user) {
        try (Connection conn = dataSource.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM user_roles WHERE user_id = ?")) {
                    ps.setString(1, user.getId());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM users WHERE id = ?")) {
                    ps.setString(1, user.getId());
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
            log.error("Failed to delete user {}", user.getId(), e);
            throw new RuntimeException("Failed to delete user " + user.getId(), e);
        }
    }

    @Override
    public boolean existsByUsername(String username) {
        return exists("username", username);
    }

    @Override
    public boolean existsByEmail(String email) {
        return exists("email", email);
    }

    private boolean exists(String column, String value) {
        String sql = "SELECT 1 FROM users WHERE " + column + " = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.error("Failed to check user existence by {}", column, e);
            throw new RuntimeException("Failed to check user existence by " + column, e);
        }
    }

    private User mapUser(ResultSet rs) throws SQLException {
        User user = new User();
        user.setId(rs.getString("id"));
        user.setUsername(rs.getString("username"));
        user.setEmail(rs.getString("email"));
        user.setPasswordHash(rs.getString("password_hash"));
        user.setFirstName(rs.getString("first_name"));
        user.setLastName(rs.getString("last_name"));
        user.setEnabled(rs.getBoolean("enabled"));
        user.setAccountNonExpired(rs.getBoolean("account_non_expired"));
        user.setAccountNonLocked(rs.getBoolean("account_non_locked"));
        user.setCredentialsNonExpired(rs.getBoolean("credentials_non_expired"));
        user.setCreatedAt(localDateTime(rs.getTimestamp("created_at")));
        user.setUpdatedAt(localDateTime(rs.getTimestamp("updated_at")));
        user.setLastLoginAt(localDateTime(rs.getTimestamp("last_login_at")));
        return user;
    }

    /**
     * Reidrata um {@link Role} pelo nome a partir das definições built-in, para
     * preservar as permissões associadas. Nomes desconhecidos viram um papel sem
     * permissões (fail-safe: nega em vez de conceder).
     */
    private static Role rehydrateRole(String name) {
        if (name == null) {
            return new Role(null, null, null);
        }
        return switch (name) {
            case Role.ROLE_ADMIN -> Role.createAdminRole();
            case Role.ROLE_DESIGNER -> Role.createDesignerRole();
            case Role.ROLE_EXECUTOR -> Role.createExecutorRole();
            case Role.ROLE_VIEWER -> Role.createViewerRole();
            default -> new Role(null, name, name);
        };
    }

    private static Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private static LocalDateTime localDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
