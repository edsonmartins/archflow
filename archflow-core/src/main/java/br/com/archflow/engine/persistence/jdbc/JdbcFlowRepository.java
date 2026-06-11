package br.com.archflow.engine.persistence.jdbc;

import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.model.flow.Flow;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implementação JDBC do FlowRepository — definição do fluxo armazenada como
 * JSON na tabela {@code flows} (ver migration {@code V002__create_flows.sql}).
 *
 * <p>A (de)serialização é delegada a um {@link FlowJsonCodec} fornecido pelo
 * caller, já que as implementações concretas de {@link Flow} vivem nas camadas
 * superiores.
 */
public class JdbcFlowRepository implements FlowRepository {

    private static final Logger logger = Logger.getLogger(JdbcFlowRepository.class.getName());

    private final DataSource dataSource;
    private final FlowJsonCodec codec;

    public JdbcFlowRepository(DataSource dataSource, FlowJsonCodec codec) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource cannot be null");
        }
        if (codec == null) {
            throw new IllegalArgumentException("codec cannot be null");
        }
        this.dataSource = dataSource;
        this.codec = codec;
    }

    @Override
    public void save(Flow flow) {
        // Upsert portável (PostgreSQL, H2, MySQL): UPDATE primeiro e INSERT
        // quando não existe linha — `ON CONFLICT ... DO UPDATE` não é aceito
        // pelo H2 usado nos testes. Uma corrida INSERT×INSERT falha alto na
        // PK, o que é preferível a silenciosamente sobrescrever.
        String updateSql = "UPDATE flows SET definition = ?, updated_at = ? WHERE id = ?";
        String insertSql = "INSERT INTO flows (id, definition, updated_at) VALUES (?, ?, ?)";

        String json;
        try {
            json = codec.toJson(flow);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Erro ao serializar fluxo: " + flow.getId(), e);
            throw new RuntimeException("Failed to serialize flow " + flow.getId(), e);
        }

        Timestamp now = Timestamp.from(Instant.now());
        try (Connection conn = dataSource.getConnection()) {
            int updated;
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setString(1, json);
                ps.setTimestamp(2, now);
                ps.setString(3, flow.getId());
                updated = ps.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    ps.setString(1, flow.getId());
                    ps.setString(2, json);
                    ps.setTimestamp(3, now);
                    ps.executeUpdate();
                }
            }
            logger.fine("Fluxo salvo: " + flow.getId());

        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Erro ao salvar fluxo: " + flow.getId(), e);
            throw new RuntimeException("Failed to save flow " + flow.getId(), e);
        }
    }

    @Override
    public Optional<Flow> findById(String id) {
        String sql = "SELECT definition FROM flows WHERE id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String json = rs.getString("definition");
                return Optional.of(codec.fromJson(json));
            }

        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Erro ao recuperar fluxo: " + id, e);
            throw new RuntimeException("Failed to load flow " + id, e);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Erro ao deserializar fluxo: " + id, e);
            throw new RuntimeException("Failed to deserialize flow " + id, e);
        }
    }

    @Override
    public void delete(String id) {
        String sql = "DELETE FROM flows WHERE id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, id);
            ps.executeUpdate();

        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Erro ao remover fluxo: " + id, e);
            throw new RuntimeException("Failed to delete flow " + id, e);
        }
    }
}
