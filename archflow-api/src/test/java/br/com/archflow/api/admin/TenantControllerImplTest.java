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
    @DisplayName("should return stats")
    void shouldReturnStats() {
        var stats = controller.getStats();
        assertThat(stats.totalActive()).isGreaterThanOrEqualTo(1);
        assertThat(stats.executionsToday()).isGreaterThan(0);
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
}
