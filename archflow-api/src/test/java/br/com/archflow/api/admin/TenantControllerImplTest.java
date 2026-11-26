package br.com.archflow.api.admin;

import br.com.archflow.api.admin.dto.TenantDto;
import br.com.archflow.api.admin.dto.TenantDto.*;
import br.com.archflow.api.admin.impl.TenantControllerImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("TenantControllerImpl")
class TenantControllerImplTest {

    private TenantControllerImpl controller;

    @BeforeEach
    void setUp() {
        controller = new TenantControllerImpl();
    }

    @Test
    @DisplayName("should list seeded tenants")
    void shouldListTenants() {
        var tenants = controller.listTenants();
        assertThat(tenants).isNotEmpty();
        assertThat(tenants).extracting(TenantDto::id).contains("tenant_rio_quality");
    }

    @Test
    @DisplayName("os totais somam o uso medido — o seed de demonstração não traz número inventado")
    void statsSumMeasuredUsage() {
        assertThat(controller.getStats().executionsToday()).as("sem fonte medida: zero, não 340").isZero();
        assertThat(controller.getStats().tokensThisMonth()).isZero();

        controller.setUsoMedido(id -> "__default__".equals(id)
                ? new TenantDto.TenantUsageDto(1, 44, 1, 1)
                : new TenantDto.TenantUsageDto(3, 12_000, 2, 1));

        var stats = controller.getStats();
        assertThat(stats.totalActive()).isGreaterThanOrEqualTo(1);
        // O tenant padrão não está no cadastro, mas o uso dele entra nos totais (é onde cai o login sem
        // claim de tenant) — a mesma base de Uso e cobrança.
        assertThat(stats.executionsToday()).isEqualTo(3 + 1);
        assertThat(stats.tokensThisMonth()).isEqualTo(12_000 + 44);
        assertThat(controller.getTenant("tenant_rio_quality").usage().tokensThisMonth()).isEqualTo(12_000);
        assertThat(controller.listTenants()).allSatisfy(t -> assertThat(t.usage().workflowCount()).isEqualTo(2));
    }

    @Test
    @DisplayName("should create new tenant")
    void shouldCreateTenant() {
        var request = new CreateTenantRequest("Acme Corp", "tenant_acme", "admin@acme.com",
                "Fintech", "professional", null, null, List.of("gpt-4o"));
        var tenant = controller.createTenant(request);

        assertThat(tenant.id()).isEqualTo("tenant_acme");
        assertThat(tenant.name()).isEqualTo("Acme Corp");
        assertThat(tenant.plan()).isEqualTo("professional");
        assertThat(tenant.status()).isEqualTo("active");
        assertThat(controller.listTenants()).hasSize(2);
    }

    @Test
    @DisplayName("should create trial tenant with trial status")
    void shouldCreateTrialTenant() {
        var request = new CreateTenantRequest("Trial Co", "tenant_trial", "a@b.com",
                "Tech", "trial", null, null, null);
        var tenant = controller.createTenant(request);
        assertThat(tenant.status()).isEqualTo("trial");
    }

    @Test
    @DisplayName("should get tenant by id")
    void shouldGetTenant() {
        var tenant = controller.getTenant("tenant_rio_quality");
        assertThat(tenant.name()).isEqualTo("Rio Quality");
    }

    @Test
    @DisplayName("should throw for non-existent tenant")
    void shouldThrowForNonExistent() {
        assertThatThrownBy(() -> controller.getTenant("nope"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should suspend tenant")
    void shouldSuspendTenant() {
        controller.suspendTenant("tenant_rio_quality");
        assertThat(controller.getTenant("tenant_rio_quality").status()).isEqualTo("suspended");
    }

    @Test
    @DisplayName("should activate tenant")
    void shouldActivateTenant() {
        controller.suspendTenant("tenant_rio_quality");
        controller.activateTenant("tenant_rio_quality");
        assertThat(controller.getTenant("tenant_rio_quality").status()).isEqualTo("active");
    }

    @Test
    @DisplayName("should delete tenant")
    void shouldDeleteTenant() {
        controller.deleteTenant("tenant_rio_quality");
        assertThat(controller.listTenants()).isEmpty();
    }

    @Test
    @DisplayName("sem limites no pedido, vale o padrão do plano gravado no /admin/global (RCHFLW-178)")
    void padroesDoPlanoNaCriacao() {
        var global = new br.com.archflow.api.admin.impl.GlobalConfigControllerImpl();
        controller.setPlanDefaults(global::planDefaults);

        TenantDto trial = controller.createTenant(new CreateTenantRequest("Novo trial", "novo_trial",
                "a@b.c", "Other", "trial", null, null, null));
        TenantDto enterprise = controller.createTenant(new CreateTenantRequest("Novo ent", "novo_ent",
                "a@b.c", "Other", "enterprise", null, null, null));

        assertThat(trial.limits()).extracting(TenantLimitsDto::executionsPerDay, TenantLimitsDto::tokensPerMonth,
                TenantLimitsDto::maxWorkflows, TenantLimitsDto::maxUsers).containsExactly(50, 100_000L, 3, 2);
        assertThat(enterprise.limits()).extracting(TenantLimitsDto::executionsPerDay,
                TenantLimitsDto::tokensPerMonth).containsExactly(1000, 10_000_000L);

        // Um padrão editado no /admin/global vale para o próximo tenant do plano.
        global.updatePlanDefaults("trial", new br.com.archflow.api.admin.dto.GlobalConfigDto.PlanDefaultsDto(
                "trial", 7, 7_000, 1, 1));
        TenantDto outro = controller.createTenant(new CreateTenantRequest("Outro", "outro_trial",
                "a@b.c", "Other", "trial", null, null, null));
        assertThat(outro.limits().executionsPerDay()).isEqualTo(7);

        // Limites explícitos no pedido continuam vencendo.
        TenantDto explicito = controller.createTenant(new CreateTenantRequest("Explícito", "explicito",
                "a@b.c", "Other", "trial", null, new TenantLimitsDto(9, 9_000, 9, 9, List.of(), List.of()), null));
        assertThat(explicito.limits().executionsPerDay()).isEqualTo(9);
    }
}
