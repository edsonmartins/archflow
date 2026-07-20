package br.com.archflow.api.admin;

import br.com.archflow.api.admin.dto.GlobalConfigDto.FeatureTogglesDto;
import br.com.archflow.api.admin.dto.GlobalConfigDto.PlanDefaultsDto;
import br.com.archflow.api.admin.dto.GlobalConfigDto.ToggleModelRequest;
import br.com.archflow.api.admin.impl.GlobalConfigControllerImpl;
import br.com.archflow.api.admin.store.InMemoryGlobalConfigStore;
import br.com.archflow.api.audit.AuditTrail;
import br.com.archflow.observability.audit.AuditAction;
import br.com.archflow.observability.audit.InMemoryAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(other.getModels())
                .filteredOn(m -> m.id().equals("gpt-4o"))
                .singleElement()
                .satisfies(m -> assertThat(m.status()).isEqualTo("deprecated"));
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
    @DisplayName("usage por tenant é vazio — nunca dados fabricados")
    void usageIsEmptyNeverFabricated() {
        var usage = controller.getUsageByTenant("2026-04");

        assertThat(usage).isEmpty();
    }

    @Test
    @DisplayName("csv de usage tem só o cabeçalho — sem tenants fabricados")
    void usageCsvHeaderOnly() {
        String csv = controller.exportUsageCsv("2026-04");

        assertThat(csv).startsWith(
                "tenantId,tenantName,executions,tokensInput,tokensOutput,estimatedCost,percentOfTotal,planLimit");
        assertThat(csv.lines().count()).isEqualTo(1);
        assertThat(csv).doesNotContain("Acme Corp").doesNotContain("Demo Trial")
                .doesNotContain("Rio Quality");
    }

    @Test
    @DisplayName("valor corrompido no store recai nos defaults em vez de quebrar")
    void corruptStoreValueFallsBackToDefaults() {
        store.put("models", "{not json");

        assertThat(controller.getModels()).anyMatch(m -> m.id().equals("gpt-4o"));
    }
}
