package br.com.archflow.api.usage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A migration {@code V6_13_1} como está no repositório e o {@link JdbcRepositorioDeUso} sobre ela:
 * o relato do mesmo contador substitui a linha, nulos continuam nulos e o período filtra pelo
 * primeiro registro.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Consumo de modelo durável (PostgreSQL real)")
class JdbcRepositorioDeUsoPostgresTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static PGSimpleDataSource dataSource;

    @BeforeAll
    static void migrate() throws Exception {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        String ddl;
        try (var in = JdbcRepositorioDeUsoPostgresTest.class.getResourceAsStream(
                "/db/migration/V6_13_1__create_llm_usage_entries.sql")) {
            ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute(ddl);
        }
    }

    @Test
    @DisplayName("upsert pelo id do contador, nulos preservados, consulta por execução e por período")
    void upsertAndQueries() {
        var repositorio = new JdbcRepositorioDeUso(dataSource);
        Instant primeiro = Instant.parse("2026-11-10T10:00:00Z").truncatedTo(ChronoUnit.MILLIS);
        Instant depois = primeiro.plusSeconds(60);
        repositorio.salvar(new LancamentoDeUso("c-1", "acme", null, LancamentoDeUso.ORIGEM_AGENTE, "openrouter/m",
                100L, 10L, null, null, null, 1, primeiro, primeiro));
        repositorio.salvar(new LancamentoDeUso("c-1", "acme", "exec-1", LancamentoDeUso.ORIGEM_FLUXO,
                "openrouter/m", 300L, 30L, 2L, 0.015, 0.001, 3, depois, depois));

        var linhas = repositorio.daExecucao("acme", "exec-1");
        assertThat(linhas).singleElement().satisfies(l -> {
            assertThat(l.tokensEntrada()).isEqualTo(300L);
            assertThat(l.custoCentavos()).isEqualTo(2L);
            assertThat(l.custoReais()).isEqualTo(0.015);
            assertThat(l.origem()).isEqualTo(LancamentoDeUso.ORIGEM_FLUXO);
            assertThat(l.registradoEm()).as("o primeiro registro decide o mês").isEqualTo(primeiro);
            assertThat(l.atualizadoEm()).isEqualTo(depois);
        });
        repositorio.salvar(new LancamentoDeUso("c-2", "globex", null, LancamentoDeUso.ORIGEM_AG_UI, null,
                null, null, null, null, null, 1, primeiro, primeiro));
        assertThat(repositorio.doPeriodo(Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z")))
                .extracting(LancamentoDeUso::id).containsExactlyInAnyOrder("c-1", "c-2");
        assertThat(repositorio.doPeriodo(Instant.parse("2026-12-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z")))
                .isEmpty();
        assertThat(repositorio.daExecucao("globex", "exec-1")).isEmpty();
        assertThat(repositorio.doPeriodo(primeiro, depois)).filteredOn(l -> l.id().equals("c-2")).singleElement()
                .satisfies(l -> assertThat(l.tokensEntrada()).isNull());
    }
}
