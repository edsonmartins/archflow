package br.com.archflow.observability.audit;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A leitura do {@link JdbcAuditRepository} devolve o que foi gravado (RCHFLW-164): o contexto ia para a
 * coluna e voltava vazio. Também: o filtro por autor casa com o id ou com o username (RCHFLW-195 item
 * 10), e a paginação não repete nem pula linhas com o mesmo timestamp (o export do CSV lê por páginas,
 * RCHFLW-180).
 */
@DisplayName("JdbcAuditRepository — leitura")
class JdbcAuditRepositoryLeituraTest {

    private JdbcAuditRepository repo;

    @BeforeEach
    void monta() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:audit-leitura-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
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
    }

    @Test
    @DisplayName("o contexto gravado volta na leitura — por id e pela consulta paginada")
    void contextoVolta() {
        AuditEvent gravado = AuditEvent.builder()
                .timestamp(Instant.now())
                .action(AuditAction.WORKFLOW_EXECUTE)
                .userId("u-1")
                .resourceType("workflow")
                .resourceId("wf-1")
                .addContext("executionId", "exec-5bf47251")
                .addContext("tenant", "__default__")
                .addContext("name", "Fluxo com \"aspas\", vírgula e\nquebra")
                .success(true)
                .build();
        repo.save(gravado);

        AuditEvent porId = repo.findById(gravado.getId()).orElseThrow();
        assertThat(porId.getContext())
                .containsEntry("executionId", "exec-5bf47251")
                .containsEntry("tenant", "__default__")
                .containsEntry("name", "Fluxo com \"aspas\", vírgula e\nquebra")
                .hasSize(3);

        List<AuditEvent> pagina = repo.query(AuditRepository.AuditQuery.builder().limit(10));
        assertThat(pagina).singleElement()
                .satisfies(e -> assertThat(e.getContext()).containsEntry("executionId", "exec-5bf47251"));
    }

    @Test
    @DisplayName("caractere de controle no contexto (nome colado com U+000B) volta nas chaves separadas")
    void contextoComCaractereDeControle() {
        AuditEvent gravado = AuditEvent.builder()
                .timestamp(Instant.now())
                .action(AuditAction.CREATE)
                .userId("u-1")
                .addContext("name", "Fluxo\u000bcolado\u0001")
                .addContext("tenant", "__default__")
                .success(true)
                .build();
        repo.save(gravado);

        assertThat(repo.findById(gravado.getId()).orElseThrow().getContext())
                .containsEntry("name", "Fluxo\u000bcolado\u0001")
                .containsEntry("tenant", "__default__")
                .doesNotContainKey(JdbcAuditRepository.RAW_CONTEXT_KEY);
        assertThat(repo.query(AuditRepository.AuditQuery.builder().contextEquals("tenant", "__default__")
                .limit(10))).hasSize(1);
    }

    @Test
    @DisplayName("linha antiga, gravada com o caractere de controle cru, também volta nas chaves separadas")
    void linhaAntigaComCaractereDeControleCru() {
        assertThat(JdbcAuditRepository.deserializeContext("{\"name\":\"a\u000bb\",\"tenant\":\"t-1\"}"))
                .containsEntry("name", "a\u000bb")
                .containsEntry("tenant", "t-1")
                .hasSize(2);
    }

    @Test
    @DisplayName("sem contexto gravado, contexto vazio (não nulo)")
    void semContexto() {
        AuditEvent gravado = AuditEvent.builder().timestamp(Instant.now()).action(AuditAction.LOGIN_SUCCESS)
                .userId("u-1").success(true).build();
        repo.save(gravado);

        assertThat(repo.findById(gravado.getId()).orElseThrow().getContext()).isEmpty();
    }

    @Test
    @DisplayName("um contexto que não é JSON volta inteiro, numa chave só, em vez de sumir")
    void contextoNaoJson() {
        assertThat(JdbcAuditRepository.deserializeContext("tenant=a"))
                .containsExactly(java.util.Map.entry(JdbcAuditRepository.RAW_CONTEXT_KEY, "tenant=a"));
        assertThat(JdbcAuditRepository.deserializeContext("{\"n\":3}")).containsEntry("n", "3");
    }

    @Test
    @DisplayName("o filtro por autor casa com o id ou com o username")
    void filtroPorAutor() {
        repo.save(AuditEvent.builder().timestamp(Instant.now()).action(AuditAction.CREATE)
                .userId("user-74023bcc").username("admin").success(true).build());
        repo.save(AuditEvent.builder().timestamp(Instant.now()).action(AuditAction.CREATE)
                .userId("user-74023bcc").success(true).build());
        repo.save(AuditEvent.builder().timestamp(Instant.now()).action(AuditAction.CREATE)
                .userId("outro").username("maria").success(true).build());

        assertThat(repo.count(AuditRepository.AuditQuery.builder().actor("admin"))).isEqualTo(1);
        assertThat(repo.count(AuditRepository.AuditQuery.builder().actor("user-74023bcc"))).isEqualTo(2);
        assertThat(repo.query(AuditRepository.AuditQuery.builder().actor("maria")))
                .extracting(AuditEvent::getUserId).containsExactly("outro");
    }

    @Test
    @DisplayName("páginas sobre linhas do mesmo instante não repetem nem pulam nenhuma")
    void paginacaoEstavel() {
        Instant mesmoInstante = Instant.parse("2026-11-26T10:00:00Z");
        for (int i = 0; i < 30; i++) {
            repo.save(AuditEvent.builder().timestamp(mesmoInstante).action(AuditAction.CREATE)
                    .userId("u-" + i).success(true).build());
        }
        Set<String> vistos = new HashSet<>();
        for (int pagina = 0; pagina < 6; pagina++) {
            repo.query(AuditRepository.AuditQuery.builder().limit(5).offset(pagina * 5))
                    .forEach(e -> assertThat(vistos.add(e.getId())).as("repetido: %s", e.getId()).isTrue());
        }
        assertThat(vistos).hasSize(30);
    }
}
