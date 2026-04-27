package br.com.archflow.api.skills;

import br.com.archflow.api.config.ImpersonationFilter;
import br.com.archflow.api.skills.dto.SkillDto;
import br.com.archflow.api.skills.impl.SkillsControllerImpl;
import br.com.archflow.langchain4j.skills.Skill;
import br.com.archflow.langchain4j.skills.SkillsManager;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("SkillsControllerImpl — per-tenant active set")
class SkillsControllerImplTest {

    private SkillsManager manager;
    private SkillsControllerImpl controller;

    @BeforeEach
    void setUp() {
        manager = new SkillsManager();
        manager.register(new Skill("docx", "Word docs", "use docx", List.of()));
        manager.register(new Skill("pdf", "PDF docs", "use pdf", List.of()));
        controller = new SkillsControllerImpl(manager);
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
    @DisplayName("activations on one tenant do not leak into another tenant's active list")
    void perTenantActiveSetIsolation() {
        bindTenant("tenant_a");
        controller.activate("docx");

        // Tenant A sees docx active.
        List<SkillDto> activeForA = controller.listActiveSkills();
        assertThat(activeForA).extracting(SkillDto::name).containsExactly("docx");

        // Tenant B sees nothing active even though manager.listActiveSkills()
        // includes docx (the manager mirrors the global set, but the controller
        // reports only what was activated in the current tenant scope).
        bindTenant("tenant_b");
        List<SkillDto> activeForB = controller.listActiveSkills();
        assertThat(activeForB).isEmpty();
    }

    @Test
    @DisplayName("listSkills marks active=true only for the current tenant's activations")
    void listSkillsRespectsTenant() {
        bindTenant("tenant_a");
        controller.activate("pdf");

        // Tenant A: pdf=active, docx=inactive.
        List<SkillDto> all = controller.listSkills();
        assertThat(all).extracting(SkillDto::name, SkillDto::active)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("docx", false),
                        org.assertj.core.groups.Tuple.tuple("pdf", true));

        // Tenant B: nothing active.
        bindTenant("tenant_b");
        all = controller.listSkills();
        assertThat(all).extracting(SkillDto::active).containsOnly(false);
    }
}
