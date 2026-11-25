package br.com.archflow.api.usage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link RepositorioDeUso} na tabela {@code llm_usage_entries} (migration {@code V6_13_1}).
 *
 * <p>O relato do mesmo contador substitui a linha — ver {@link LancamentoDeUso#atualizadoPor}. A
 * leitura e a escrita vão na mesma transação, com a linha travada ({@code FOR UPDATE}), para dois
 * relatos simultâneos do mesmo contador não se sobrescreverem pela metade.
 */
public final class JdbcRepositorioDeUso implements RepositorioDeUso {

    private static final Logger log = LoggerFactory.getLogger(JdbcRepositorioDeUso.class);
    private static final String COLUNAS = "id, tenant_id, flow_execution_id, origin, model, input_tokens, "
            + "output_tokens, cost_cents, cost_reais, declared_cost_usd, turns, recorded_at, updated_at";

    private final DataSource dataSource;

    public JdbcRepositorioDeUso(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void salvar(LancamentoDeUso lancamento) {
        try (Connection conn = dataSource.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                LancamentoDeUso existente = null;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT " + COLUNAS + " FROM llm_usage_entries WHERE id = ? FOR UPDATE")) {
                    ps.setString(1, lancamento.id());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            existente = mapear(rs);
                        }
                    }
                }
                if (existente == null) {
                    inserir(conn, lancamento);
                } else {
                    atualizar(conn, existente.atualizadoPor(lancamento));
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            log.error("Falha ao gravar o uso {} (tenant {})", lancamento.id(), lancamento.tenantId(), e);
            throw new IllegalStateException("Failed to save usage entry " + lancamento.id(), e);
        }
    }

    private static void inserir(Connection conn, LancamentoDeUso l) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO llm_usage_entries (" + COLUNAS
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, l.id());
            ps.setString(2, l.tenantId());
            preencher(ps, 3, l);
            ps.setTimestamp(12, Timestamp.from(l.registradoEm()));
            ps.setTimestamp(13, Timestamp.from(l.atualizadoEm()));
            ps.executeUpdate();
        }
    }

    private static void atualizar(Connection conn, LancamentoDeUso l) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE llm_usage_entries SET flow_execution_id = ?, "
                + "origin = ?, model = ?, input_tokens = ?, output_tokens = ?, cost_cents = ?, cost_reais = ?, "
                + "declared_cost_usd = ?, turns = ?, updated_at = ? WHERE id = ?")) {
            preencher(ps, 1, l);
            ps.setTimestamp(10, Timestamp.from(l.atualizadoEm()));
            ps.setString(11, l.id());
            ps.executeUpdate();
        }
    }

    /** As nove colunas que um relato muda, a partir de {@code inicio}. */
    private static void preencher(PreparedStatement ps, int inicio, LancamentoDeUso l) throws SQLException {
        ps.setString(inicio, l.execucaoDeFluxoId());
        ps.setString(inicio + 1, l.origem());
        ps.setString(inicio + 2, l.modelo());
        setLong(ps, inicio + 3, l.tokensEntrada());
        setLong(ps, inicio + 4, l.tokensSaida());
        setLong(ps, inicio + 5, l.custoCentavos());
        setDouble(ps, inicio + 6, l.custoReais());
        setDouble(ps, inicio + 7, l.custoDeclaradoUsd());
        if (l.turnos() == null) {
            ps.setNull(inicio + 8, Types.INTEGER);
        } else {
            ps.setInt(inicio + 8, l.turnos());
        }
    }

    @Override
    public List<LancamentoDeUso> doPeriodo(Instant inicio, Instant fim) {
        return consultar("SELECT " + COLUNAS + " FROM llm_usage_entries WHERE recorded_at >= ? AND recorded_at < ?",
                ps -> {
                    ps.setTimestamp(1, Timestamp.from(inicio));
                    ps.setTimestamp(2, Timestamp.from(fim));
                });
    }

    @Override
    public List<LancamentoDeUso> daExecucao(String tenantId, String execucaoDeFluxoId) {
        if (execucaoDeFluxoId == null) {
            return List.of();
        }
        return consultar("SELECT " + COLUNAS + " FROM llm_usage_entries WHERE tenant_id = ? AND flow_execution_id = ?",
                ps -> {
                    ps.setString(1, tenantId);
                    ps.setString(2, execucaoDeFluxoId);
                });
    }

    private interface Parametros {
        void aplicar(PreparedStatement ps) throws SQLException;
    }

    private List<LancamentoDeUso> consultar(String sql, Parametros parametros) {
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            parametros.aplicar(ps);
            List<LancamentoDeUso> resultado = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    resultado.add(mapear(rs));
                }
            }
            return resultado;
        } catch (SQLException e) {
            log.error("Falha ao ler o uso de modelo", e);
            throw new IllegalStateException("Failed to read usage entries", e);
        }
    }

    private static LancamentoDeUso mapear(ResultSet rs) throws SQLException {
        return new LancamentoDeUso(rs.getString("id"), rs.getString("tenant_id"),
                rs.getString("flow_execution_id"), rs.getString("origin"), rs.getString("model"),
                getLong(rs, "input_tokens"), getLong(rs, "output_tokens"), getLong(rs, "cost_cents"),
                getDouble(rs, "cost_reais"), getDouble(rs, "declared_cost_usd"), getInt(rs, "turns"),
                rs.getTimestamp("recorded_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private static void setLong(PreparedStatement ps, int i, Long v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.BIGINT);
        } else {
            ps.setLong(i, v);
        }
    }

    private static void setDouble(PreparedStatement ps, int i, Double v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.DOUBLE);
        } else {
            ps.setDouble(i, v);
        }
    }

    private static Long getLong(ResultSet rs, String c) throws SQLException {
        long v = rs.getLong(c);
        return rs.wasNull() ? null : v;
    }

    private static Double getDouble(ResultSet rs, String c) throws SQLException {
        double v = rs.getDouble(c);
        return rs.wasNull() ? null : v;
    }

    private static Integer getInt(ResultSet rs, String c) throws SQLException {
        int v = rs.getInt(c);
        return rs.wasNull() ? null : v;
    }
}
