package br.com.archflow.api.admin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("ImpersonationContext")
class ImpersonationContextTest {

    @AfterEach
    void cleanup() {
        ImpersonationContext.clear();
    }

    @Test
    @DisplayName("should not be impersonating by default")
    void shouldNotBeImpersonatingByDefault() {
        assertThat(ImpersonationContext.isImpersonating()).isFalse();
        assertThat(ImpersonationContext.getTenantId()).isNull();
    }

    @Test
    @DisplayName("should set and get impersonation")
    void shouldSetAndGet() {
        ImpersonationContext.set("tenant_acme", "superadmin-1");

        assertThat(ImpersonationContext.isImpersonating()).isTrue();
        assertThat(ImpersonationContext.getTenantId()).isEqualTo("tenant_acme");
        assertThat(ImpersonationContext.getActorId()).isEqualTo("superadmin-1");
    }

    @Test
    @DisplayName("should resolve effective tenant — impersonated over JWT")
    void shouldResolveEffective() {
        ImpersonationContext.set("tenant_acme", "superadmin-1");
        assertThat(ImpersonationContext.resolveEffectiveTenant("tenant_from_jwt")).isEqualTo("tenant_acme");
    }

    @Test
    @DisplayName("should resolve effective tenant — JWT when not impersonating")
    void shouldResolveJwtWhenNotImpersonating() {
        assertThat(ImpersonationContext.resolveEffectiveTenant("tenant_from_jwt")).isEqualTo("tenant_from_jwt");
    }

    @Test
    @DisplayName("should clear impersonation")
    void shouldClear() {
        ImpersonationContext.set("tenant_acme", "admin");
        ImpersonationContext.clear();

        assertThat(ImpersonationContext.isImpersonating()).isFalse();
        assertThat(ImpersonationContext.getTenantId()).isNull();
    }
}
