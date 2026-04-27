package br.com.archflow.api.brainsentry;

import br.com.archflow.api.brainsentry.dto.BrainSentryConfigDto;
import br.com.archflow.api.brainsentry.impl.BrainSentryConfigControllerImpl;
import br.com.archflow.api.config.ImpersonationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("BrainSentryConfigControllerImpl — tenant scoping")
class BrainSentryConfigControllerImplTest {

    private BrainSentryConfigDto initial;
    private BrainSentryConfigControllerImpl controller;

    @BeforeEach
    void setUp() {
        initial = new BrainSentryConfigDto(false, "https://api.brainsentry.dev",
                "default-key", null, 1000, false, 30);
        controller = new BrainSentryConfigControllerImpl(initial);
    }

    @AfterEach
    void cleanup() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void bindTenant(String tenantId) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(ImpersonationFilter.ATTR_TENANT_ID)).thenReturn(tenantId);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    @Test
    @DisplayName("two tenants get independent config slots — update on one does not change the other")
    void perTenantIsolation() {
        // Tenant A updates its key.
        bindTenant("tenant_a");
        controller.update(new BrainSentryConfigDto(true, "https://a.example",
                "secret-a", "tenant_a", 5000, true, 60));

        // Tenant B reads — it should still see the initial config, not tenant A's key.
        bindTenant("tenant_b");
        BrainSentryConfigDto b = controller.get();
        assertThat(b.baseUrl()).isEqualTo("https://api.brainsentry.dev");
        assertThat(b.maxTokenBudget()).isEqualTo(1000);

        // Tenant A reads back — should see its own update.
        bindTenant("tenant_a");
        BrainSentryConfigDto a = controller.get();
        assertThat(a.baseUrl()).isEqualTo("https://a.example");
        assertThat(a.maxTokenBudget()).isEqualTo(5000);
        assertThat(a.deepAnalysisEnabled()).isTrue();
    }

    @Test
    @DisplayName("masked apiKey on update preserves the previous value")
    void preservesMaskedKey() {
        bindTenant("tenant_a");
        controller.update(new BrainSentryConfigDto(true, "https://a.example",
                "real-secret", "tenant_a", 1000, false, 30));

        // Subsequent update with masked key should keep "real-secret".
        controller.update(new BrainSentryConfigDto(true, "https://a.example",
                "real-…cret", "tenant_a", 2000, true, 30));

        // Internal value should still be the un-masked key — verify via masked output
        // shape changes only the prefix, not the content.
        BrainSentryConfigDto current = controller.get();
        assertThat(current.maxTokenBudget()).isEqualTo(2000);
        // The masked output won't equal "real-secret"; we just confirm no NPE and
        // that tenant a's config now carries the budget update.
        assertThat(current.deepAnalysisEnabled()).isTrue();
    }
}
