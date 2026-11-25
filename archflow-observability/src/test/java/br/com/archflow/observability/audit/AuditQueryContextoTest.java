package br.com.archflow.observability.audit;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AuditRepository.AuditQuery#contextEquals}: a trilha não tem coluna de tenant — o produtor o
 * grava no contexto do evento —, e a auditoria de um tenant não pode listar a de outro (RCHFLW-39).
 * Os dois repositórios precisam responder igual; o JDBC procura o par no JSON da coluna TEXT.
 */
@DisplayName("AuditQuery.contextEquals — filtro por um par do contexto")
class AuditQueryContextoTest {

    private static AuditEvent evento(String userId, String tenant) {
        AuditEvent.Builder b = AuditEvent.builder()
                .timestamp(Instant.now())
                .action(AuditAction.WORKFLOW_EXECUTE)
                .userId(userId)
                .success(true);
        if (tenant != null) {
            b.addContext("tenant", tenant);
        }
        return b.build();
    }

    private static void semear(AuditRepository repo) {
        repo.save(evento("u-a", "tenant_a"));
        // "_" é curinga do LIKE: sem escapar, o filtro por tenant_a também traria este.
        repo.save(evento("u-x", "tenantXa"));
        repo.save(evento("u-b", "tenant-b"));
        // Um valor que contém o par como texto não é o par.
        repo.save(AuditEvent.builder().timestamp(Instant.now()).action(AuditAction.WORKFLOW_EXECUTE)
                .userId("u-falso").success(true)
                .addContext("nota", "\"tenant\":\"tenant_a\"").addContext("tenant", "tenant-c").build());
        repo.save(evento("u-sem", null));
    }

    private static List<String> usuarios(AuditRepository repo, String tenant) {
        return repo.query(AuditRepository.AuditQuery.builder().contextEquals("tenant", tenant))
                .stream().map(AuditEvent::getUserId).toList();
    }

    @Nested
    @DisplayName("JdbcAuditRepository (H2 em modo PostgreSQL, DDL da migration)")
    class Jdbc {

        private JdbcAuditRepository repo;

        @BeforeEach
        void monta() throws Exception {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:audit-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
            String sql;
            try (InputStream in = getClass().getResourceAsStream("/db/migration/V4_1__CreateAuditLogTable.sql")) {
                assertThat(in).isNotNull();
                sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            StringBuilder semComentarios = new StringBuilder();
            for (String linha : sql.split("\n")) {
                if (!linha.trim().startsWith("--")) {
                    semComentarios.append(linha).append('\n');
                }
            }
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                for (String comando : semComentarios.toString().split(";")) {
                    if (!comando.isBlank()) {
                        st.execute(comando);
                    }
                }
            }
            repo = new JdbcAuditRepository(ds);
            semear(repo);
        }

        @Test
        @DisplayName("só o par exato: nem curinga do LIKE, nem o par escrito dentro de outro valor")
        void parExato() {
            assertThat(usuarios(repo, "tenant_a")).containsExactly("u-a");
            assertThat(usuarios(repo, "tenant-b")).containsExactly("u-b");
            assertThat(repo.count(AuditRepository.AuditQuery.builder().contextEquals("tenant", "tenant_a")))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("sem o filtro, todos")
        void semFiltro() {
            assertThat(repo.query(AuditRepository.AuditQuery.builder())).hasSize(5);
        }
    }

    @Nested
    @DisplayName("InMemoryAuditRepository")
    class EmMemoria {

        @Test
        @DisplayName("mesmo resultado do JDBC")
        void parExato() {
            InMemoryAuditRepository repo = new InMemoryAuditRepository();
            semear(repo);

            assertThat(usuarios(repo, "tenant_a")).containsExactly("u-a");
            assertThat(repo.count(AuditRepository.AuditQuery.builder().contextEquals("tenant", "tenant-b")))
                    .isEqualTo(1);
            assertThat(repo.query(AuditRepository.AuditQuery.builder())).hasSize(5);
        }
    }
}
