package br.com.archflow.api.skills.store;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * {@link SkillActivationStore} durável sobre a tabela {@code tenant_active_skills} (migration
 * {@code V6_14__create_tenant_active_skills.sql}).
 *
 * <p>Uma linha por skill ativa: ativar insere, desativar apaga. A ausência de linha é "inativa", que é
 * o estado de uma skill que o tenant nunca ligou — o mesmo de antes da persistência.
 */
public class JdbcSkillActivationStore implements SkillActivationStore {

    /** SQLState de violação de chave única (PostgreSQL e H2). */
    private static final String UNIQUE_VIOLATION = "23505";

    private final DataSource dataSource;

    public JdbcSkillActivationStore(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource cannot be null");
        }
        this.dataSource = dataSource;
    }

    @Override
    public Set<String> activeSkills(String tenantId) {
        String sql = "SELECT skill_name FROM tenant_active_skills WHERE tenant_id = ? ORDER BY skill_name";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            Set<String> active = new LinkedHashSet<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    active.add(rs.getString(1));
                }
            }
            return Set.copyOf(active);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read active skills of tenant " + tenantId, e);
        }
    }

    @Override
    public void activate(String tenantId, String skillName) {
        // INSERT ... WHERE NOT EXISTS é portável (PostgreSQL, H2) e cobre o caso comum de ativar o que já
        // está ativo; a corrida entre duas ativações simultâneas cai na chave primária, e o estado
        // resultante é o pedido — por isso a violação de unicidade não é erro aqui.
        String sql = "INSERT INTO tenant_active_skills (tenant_id, skill_name, activated_at) "
                + "SELECT ?, ?, ? WHERE NOT EXISTS "
                + "(SELECT 1 FROM tenant_active_skills WHERE tenant_id = ? AND skill_name = ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, skillName);
            ps.setTimestamp(3, Timestamp.from(Instant.now()));
            ps.setString(4, tenantId);
            ps.setString(5, skillName);
            ps.executeUpdate();
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                return;
            }
            throw new RuntimeException("Failed to activate skill " + skillName + " for tenant " + tenantId, e);
        }
    }

    @Override
    public void deactivate(String tenantId, String skillName) {
        String sql = "DELETE FROM tenant_active_skills WHERE tenant_id = ? AND skill_name = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, skillName);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to deactivate skill " + skillName + " for tenant " + tenantId, e);
        }
    }
}
