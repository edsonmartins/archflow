package br.com.archflow.api.admin.observability;

import br.com.archflow.api.admin.observability.ObservabilityDtos.AuditEntryDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.FilterDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.OverviewDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.PageDto;
import br.com.archflow.api.admin.observability.impl.InMemoryTraceStore;
import br.com.archflow.api.admin.observability.impl.ObservabilityService;
import br.com.archflow.api.audit.AuditTrail;
import br.com.archflow.observability.audit.AuditAction;
import br.com.archflow.observability.audit.AuditEvent;
import br.com.archflow.observability.audit.InMemoryAuditRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Visão geral e auditoria da observabilidade (2º bug hunt de QA).
 *
 * <ul>
 *   <li>RCHFLW-177: execução pausada à espera de aprovação não é erro e conta uma vez.</li>
 *   <li>RCHFLW-195 item 5: "hoje" é o dia do fuso do servidor, como no Workspace.</li>
 *   <li>RCHFLW-180: o CSV aplica os filtros da tela e traz todas as linhas, com o contexto.</li>
 *   <li>RCHFLW-195 item 10: o filtro de autor casa com o username mostrado.</li>
 * </ul>
 */
@DisplayName("Observabilidade — visão geral e auditoria (QA2)")
class VisaoGeralEAuditoriaQa2Test {

    private static OverviewDto overview(InMemoryTraceStore traces, Clock clock) {
        return new ObservabilityService(null, traces, null, null, null, null, clock).getOverview("acme");
    }

    private static void trace(InMemoryTraceStore store, String id, Instant at, String status) {
        store.record(InMemoryTraceStore.trace(id, "acme", null, "wf", "exec-" + id, at, 100, status, null,
                Map.of(), List.of()));
    }

    @Nested
    @DisplayName("Visão geral")
    class VisaoGeral {

        @Test
        @DisplayName("PAUSED entra no total uma vez e fica fora das taxas de sucesso e de erro")
        void pausaNaoEErro() {
            InMemoryTraceStore traces = new InMemoryTraceStore();
            Instant agora = Instant.now();
            trace(traces, "ok-1", agora.minusSeconds(3), "OK");
            trace(traces, "ok-2", agora.minusSeconds(2), "OK");
            trace(traces, "erro", agora.minusSeconds(1), "ERROR");
            trace(traces, "pausa", agora.minusSeconds(1), "PAUSED");

            OverviewDto o = overview(traces, Clock.systemDefaultZone());

            assertThat(o.totalExecutionsToday()).isEqualTo(4);
            assertThat(o.pausedExecutionsToday()).isEqualTo(1);
            assertThat(o.successRate()).isEqualTo(2d / 3);
            assertThat(o.errorRate()).isEqualTo(1d / 3);
        }

        @Test
        @DisplayName("o dia começa à meia-noite do fuso do servidor, não à de UTC")
        void diaNoFusoDoServidor() {
            // 01:33 em -03:00 = 04:33 UTC. A execução das 22:00 locais de ontem (01:00 UTC de hoje) é de
            // ontem para o Workspace — e era de hoje para a Visão geral, que contava desde a meia-noite UTC.
            ZoneId saoPaulo = ZoneId.of("America/Sao_Paulo");
            Instant agora = Instant.parse("2026-11-26T04:33:00Z");
            InMemoryTraceStore traces = new InMemoryTraceStore();
            trace(traces, "ontem", Instant.parse("2026-11-26T01:00:00Z"), "OK");
            trace(traces, "hoje", Instant.parse("2026-11-26T03:30:00Z"), "OK");

            OverviewDto o = overview(traces, Clock.fixed(agora, saoPaulo));

            assertThat(o.totalExecutionsToday()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Auditoria")
    class Auditoria {

        private final InMemoryAuditRepository repo = new InMemoryAuditRepository();
        private final ObservabilityService service = new ObservabilityService(null, null, repo, null);

        private void evento(AuditAction action, String userId, String username, int segundosAtras) {
            repo.save(AuditEvent.builder()
                    .timestamp(Instant.now().minusSeconds(segundosAtras))
                    .action(action)
                    .userId(userId)
                    .username(username)
                    .success(true)
                    .addContext(AuditTrail.CONTEXT_TENANT, "acme")
                    .addContext("executionId", "exec-" + segundosAtras)
                    .build());
        }

        private static FilterDto filtro(String actor, String action) {
            return new FilterDto(null, null, null, null, null, actor, action, null, 0, 50);
        }

        private static List<String> linhas(String csv) {
            return Arrays.stream(csv.split("\n")).skip(1).toList();
        }

        @Test
        @DisplayName("o CSV aplica o filtro de ação e traz todas as linhas — além das 500 de antes")
        void exportFiltradoESemCorte() {
            for (int i = 0; i < 620; i++) {
                evento(AuditAction.LOGIN_SUCCESS, "u-" + i, null, 10 + i);
            }
            for (int i = 0; i < 40; i++) {
                evento(AuditAction.CREATE, "u-c" + i, null, 700 + i);
            }

            String csv = service.exportAuditCsv(filtro(null, "LOGIN_SUCCESS"));

            assertThat(linhas(csv)).hasSize(620).allSatisfy(l -> assertThat(l).contains(",LOGIN_SUCCESS,"));
            assertThat(linhas(service.exportAuditCsv(filtro(null, null)))).hasSize(660);
        }

        @Test
        @DisplayName("o CSV leva o contexto do evento numa coluna")
        void exportComContexto() {
            evento(AuditAction.WORKFLOW_EXECUTE, "u-1", "admin", 5);

            String csv = service.exportAuditCsv(filtro(null, null));

            assertThat(csv.split("\n")[0]).endsWith(",context");
            assertThat(linhas(csv)).singleElement()
                    .satisfies(l -> assertThat(l).endsWith(",executionId=exec-5; tenant=acme"));
        }

        @Test
        @DisplayName("autor: casa com o username mostrado na tela (e com o id), na lista e no CSV")
        void filtroDeAutor() {
            evento(AuditAction.CREATE, "user-74023bcc", "admin", 5);
            evento(AuditAction.CREATE, "user-outro", "maria", 6);

            PageDto<AuditEntryDto> pagina = service.listAudit(filtro("admin", null));

            assertThat(pagina.items()).extracting(AuditEntryDto::userId).containsExactly("user-74023bcc");
            assertThat(pagina.total()).isEqualTo(1);
            assertThat(linhas(service.exportAuditCsv(filtro("admin", null)))).hasSize(1);
        }

        @Test
        @DisplayName("ação desconhecida não casa com nada — antes voltavam todas as ações como se filtradas")
        void acaoDesconhecida() {
            evento(AuditAction.CREATE, "u-1", null, 5);

            assertThat(service.listAudit(filtro(null, "LOGIN")).items()).isEmpty();
            assertThat(linhas(service.exportAuditCsv(filtro(null, "LOGIN")))).isEmpty();
            // O código do evento (como no banco) também vale.
            assertThat(service.listAudit(filtro(null, "create")).items()).hasSize(1);
        }
    }
}
