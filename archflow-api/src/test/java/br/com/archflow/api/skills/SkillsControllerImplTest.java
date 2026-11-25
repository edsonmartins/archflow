package br.com.archflow.api.skills;

import br.com.archflow.api.config.ImpersonationFilter;
import br.com.archflow.api.skills.dto.SkillDto;
import br.com.archflow.api.skills.impl.SkillsControllerImpl;
import br.com.archflow.api.skills.store.InMemorySkillActivationStore;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        controller = new SkillsControllerImpl(manager, new InMemorySkillActivationStore());
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

        // Tenant B sees nothing active.
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

    @Test
    @DisplayName("RCHFLW-66: o switch não mexe no set global do SkillsManager (que não separa tenants)")
    void naoUsaOSetGlobalDoManager() {
        bindTenant("tenant_a");
        controller.activate("docx");

        assertThat(manager.isActive("docx")).isFalse();
        assertThat(manager.listActiveSkills()).isEmpty();

        manager.activateSkill("pdf");
        bindTenant("tenant_b");
        controller.deactivate("pdf");
        assertThat(manager.isActive("pdf")).isTrue();
    }

    @Test
    @DisplayName("ativar skill que não existe no catálogo é erro de entrada, e nada é gravado")
    void skillInexistente() {
        bindTenant("tenant_a");

        assertThatThrownBy(() -> controller.activate("nao-existe"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nao-existe");
        assertThat(controller.listActiveSkills()).isEmpty();
    }
}
