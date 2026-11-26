package br.com.archflow.api.admin;

import br.com.archflow.api.admin.dto.GlobalConfigDto.FeatureTogglesDto;
import br.com.archflow.api.admin.dto.GlobalConfigDto.PlanDefaultsDto;
import br.com.archflow.api.admin.dto.GlobalConfigDto.ToggleModelRequest;
import br.com.archflow.api.admin.dto.GlobalConfigDto.UsageRowDto;
import br.com.archflow.api.admin.impl.GlobalConfigControllerImpl;
import br.com.archflow.api.admin.store.InMemoryGlobalConfigStore;
import br.com.archflow.api.audit.AuditTrail;
import br.com.archflow.observability.audit.AuditAction;
import br.com.archflow.observability.audit.InMemoryAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GlobalConfigControllerImpl")
class GlobalConfigControllerImplTest {

    private InMemoryGlobalConfigStore store;
    private GlobalConfigControllerImpl controller;

    @BeforeEach
    void setUp() {
        store = new InMemoryGlobalConfigStore();
        controller = new GlobalConfigControllerImpl(store);
    }

    @Test
    @DisplayName("catálogo default de modelos quando o store está vazio")
    void defaultModelsWhenStoreEmpty() {
        var models = controller.getModels();

        assertThat(models).isNotEmpty();
        assertThat(models).anyMatch(m -> m.id().equals("gpt-4o"));
    }

    @Test
    @DisplayName("toggleModel persiste no store: outra instância com o mesmo store vê a mudança")
    void toggleModelPersistsAcrossInstances() {
        controller.toggleModel("gpt-4o", new ToggleModelRequest(false));

        var other = new GlobalConfigControllerImpl(store);
        // A lista do código tem o gpt-4o na OpenAI e na Azure: o toggle é por id e desliga os dois.
        assertThat(other.getModels())
                .filteredOn(m -> m.id().equals("gpt-4o"))
                .isNotEmpty()
                .allSatisfy(m -> assertThat(m.status()).isEqualTo("deprecated"));
    }

    @Test
    @DisplayName("updatePlanDefaults persiste no store")
    void planDefaultsPersist() {
        controller.updatePlanDefaults("trial", new PlanDefaultsDto("trial", 99, 999, 9, 9));

        var other = new GlobalConfigControllerImpl(store);
        assertThat(other.getPlanDefaults())
                .filteredOn(p -> p.plan().equals("trial"))
                .singleElement()
                .satisfies(p -> assertThat(p.executionsPerDay()).isEqualTo(99));
        // demais planos default continuam presentes
        assertThat(other.getPlanDefaults()).anyMatch(p -> p.plan().equals("enterprise"));
    }

    @Test
    @DisplayName("updateToggles persiste no store")
    void togglesPersist() {
        controller.updateToggles(new FeatureTogglesDto(false, false, false, true, true, false));

        var other = new GlobalConfigControllerImpl(store);
        assertThat(other.getToggles().debugMode()).isTrue();
        assertThat(other.getToggles().allowLocalModels()).isFalse();
    }

    @Test
    @DisplayName("audit log vazio quando não há AuditRepository configurado")
    void auditLogEmptyWithoutRepository() {
        assertThat(controller.getAuditLog(50)).isEmpty();
    }

    @Test
    @DisplayName("audit log lê do AuditRepository real (sem entradas fabricadas)")
    void auditLogReadsFromRepository() {
        var repo = new InMemoryAuditRepository();
        repo.save(br.com.archflow.observability.audit.AuditEvent.builder()
                .action(AuditAction.CONFIG_CHANGE)
                .username("root")
                .resourceType("global-config")
                .resourceId("featureToggles")
                .build());
        controller.setAuditRepository(() -> repo);

        var entries = controller.getAuditLog(10);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).actor()).isEqualTo("root");
        assertThat(entries.get(0).action()).isEqualTo("config.change");
        assertThat(entries.get(0).details()).contains("global-config:featureToggles");
    }

    @Test
    @DisplayName("mutações de config produzem eventos CONFIG_CHANGE via AuditTrail")
    void configMutationsAreAudited() {
        var repo = new InMemoryAuditRepository();
        controller.setAuditTrail(new AuditTrail(() -> repo));

        controller.toggleModel("gpt-4o", new ToggleModelRequest(false));
        controller.updateToggles(new FeatureTogglesDto(true, true, true, false, false, true));

        var events = repo.query(br.com.archflow.observability.audit.AuditRepository.AuditQuery
                .builder().limit(10));
        assertThat(events).hasSize(2);
        assertThat(events).allMatch(e -> e.getAction() == AuditAction.CONFIG_CHANGE);
    }

    @Test
    @DisplayName("usage por tenant é vazio sem medição — nunca dados fabricados")
    void usageIsEmptyNeverFabricated() {
        assertThat(controller.getUsageByTenant("2026-04")).isEmpty();

        controller.setUso(registroDeUso(), id -> java.util.Optional.empty());
        assertThat(controller.getUsageByTenant("2026-04")).isEmpty();
    }

    @Test
    @DisplayName("csv de usage tem só o cabeçalho — sem tenants fabricados")
    void usageCsvHeaderOnly() {
        String csv = controller.exportUsageCsv("2026-04");

        assertThat(csv).startsWith("\uFEFFtenantId,tenantName,executions,tokensInput,tokensOutput,costReais,"
                + "declaredCostUsd,executionsWithoutPrice,percentOfTotal,planLimit");
        assertThat(csv.lines().count()).isEqualTo(1);
        assertThat(csv).doesNotContain("Acme Corp").doesNotContain("Demo Trial")
                .doesNotContain("Rio Quality");
    }

    @Test
    @DisplayName("usage por tenant é o medido no mês: tokens, custo (ou sem preço), fatia e limite do cadastro")
    void usageByTenantIsTheMeasuredOne() {
        var registro = registroDeUso();
        registrar(registro, "acme", "openrouter/m", 3_000, 1_000);
        registrar(registro, "acme", "modelo/sem-preco", 500, 500);
        registrar(registro, "globex", "openrouter/m", 800, 200);
        var limites = new br.com.archflow.api.admin.dto.TenantDto.TenantLimitsDto(10, 5_000_000, 1, 1,
                List.of(), List.of());
        controller.setUso(registro, id -> "acme".equals(id)
                ? java.util.Optional.of(new br.com.archflow.api.admin.dto.TenantDto("acme", "Acme", "pro", "active",
                        null, null, limites, null, List.of(), null))
                : java.util.Optional.empty());

        var rows = controller.getUsageByTenant(registro.mesAtual().toString());

        assertThat(rows).extracting(UsageRowDto::tenantId).containsExactly("acme", "globex");
        var acme = rows.get(0);
        assertThat(acme.tenantName()).isEqualTo("Acme");
        assertThat(acme.executions()).isEqualTo(2);
        assertThat(acme.tokensInput()).isEqualTo(3_500);
        assertThat(acme.tokensOutput()).isEqualTo(1_500);
        assertThat(acme.costCents()).as("só a execução com preço entra na soma").isEqualTo(1L);
        assertThat(acme.executionsWithoutPrice()).isEqualTo(1);
        assertThat(acme.percentOfTotal()).isCloseTo(83.333, org.assertj.core.data.Offset.offset(0.001));
        assertThat(acme.planLimit()).isEqualTo(5_000_000L);
        var globex = rows.get(1);
        assertThat(globex.tenantName()).as("sem cadastro, o id").isEqualTo("globex");
        assertThat(globex.planLimit()).as("sem cadastro, sem limite inventado").isNull();
        assertThat(controller.getUsageByTenant(registro.mesAtual().minusMonths(1).toString())).isEmpty();
        assertThatThrownBy(() -> controller.getUsageByTenant("abril")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("tenant com uso pequeno não vira 0% (RCHFLW-195 item 7)")
    void fatiaPequenaNaoEZero() {
        var registro = registroDeUso();
        registrar(registro, "grande", "openrouter/m", 2_000_000, 0);
        registrar(registro, "pequeno", "openrouter/m", 57, 0);
        controller.setUso(registro, id -> java.util.Optional.empty());

        var pequeno = controller.getUsageByTenant(registro.mesAtual().toString()).stream()
                .filter(r -> "pequeno".equals(r.tenantId())).findFirst().orElseThrow();

        assertThat(pequeno.percentOfTotal()).isGreaterThan(0d).isLessThan(0.1);
    }

    @Test
    @DisplayName("CSV de uso: BOM UTF-8 e números em ponto fixo sem ruído do double (RCHFLW-195 item 8)")
    void csvDeUsoSemRuido() {
        assertThat(GlobalConfigControllerImpl.numero(0.007550729999999999)).isEqualTo("0.00755073");
        assertThat(GlobalConfigControllerImpl.numero(2.14E-5)).isEqualTo("0.0000214");
        assertThat(GlobalConfigControllerImpl.numero(1.0)).isEqualTo("1");
        assertThat(GlobalConfigControllerImpl.numero(null)).isEmpty();
        // Sem arredondar a centavos: o costReais é somado do outro lado (VEN-245).
        assertThat(GlobalConfigControllerImpl.numero(0.123456789)).isEqualTo("0.123456789");

        var registro = registroDeUso();
        registrar(registro, "acme", "openrouter/m", 1, 0);
        controller.setUso(registro, id -> java.util.Optional.empty());
        String csv = controller.exportUsageCsv(registro.mesAtual().toString());

        assertThat(csv).startsWith("\uFEFF");
        assertThat(csv).doesNotContain("E-");
    }

    private static br.com.archflow.api.usage.RegistroDeUso registroDeUso() {
        return new br.com.archflow.api.usage.RegistroDeUso(new br.com.archflow.api.usage.InMemoryRepositorioDeUso(),
                br.com.archflow.api.agent.mcp.TabelaDePrecos.doTexto("openrouter/m=100,200").tabela());
    }

    private static void registrar(br.com.archflow.api.usage.RegistroDeUso registro, String tenant, String modelo,
                                  int entrada, int saida) {
        var uso = new br.com.archflow.api.agent.mcp.ContadorDeUso();
        uso.somar("openrouter", modelo, entrada, saida);
        registro.registrar(tenant, null, br.com.archflow.api.usage.LancamentoDeUso.ORIGEM_AGENTE, uso);
    }

    @Test
    @DisplayName("valor corrompido no store recai nos defaults em vez de quebrar")
    void corruptStoreValueFallsBackToDefaults() {
        store.put("models", "{not json");

        assertThat(controller.getModels()).anyMatch(m -> m.id().equals("gpt-4o"));
    }
}
