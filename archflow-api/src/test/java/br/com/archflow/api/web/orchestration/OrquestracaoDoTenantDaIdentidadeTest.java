package br.com.archflow.api.web.orchestration;

import br.com.archflow.api.config.ImpersonationFilter;
import br.com.archflow.api.config.JwtAuthenticationFilter;
import br.com.archflow.api.config.TenantContext;
import br.com.archflow.api.config.TenantScope;
import br.com.archflow.api.orchestration.DynamicWorkflowRequest;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.security.jwt.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RCHFLW-39: {@code /api/orchestration/run} roda no tenant da identidade.
 *
 * <p>O controller só comparava o {@code tenantId} do corpo com a <em>claim</em> do JWT. O login de
 * hoje não emite a claim, então para qualquer token real a comparação não acontecia e o corpo
 * escolhia o tenant — e com ele a chave de LLM e os modelos do planejador (RCHFLW-79). Agora a
 * referência é o tenant efetivo da requisição (claim, tenant padrão de um token sem claim ou
 * personificação conferida pelo filtro).</p>
 */
@DisplayName("Orquestração: tenant da identidade (RCHFLW-39)")
class OrquestracaoDoTenantDaIdentidadeTest {

    private static final String SECRET = "test-secret-key-256-bits-long-enough-for-hs256-ok";

    private final JwtService jwtService = new JwtService(SECRET);
    private final DynamicWorkflowService service = mock(DynamicWorkflowService.class);

    private MockMvc montar(boolean authLigada, boolean personificacao) {
        var controller = new SpringDynamicWorkflowController(service, new TenantScope(authLigada));
        var builder = MockMvcBuilders.standaloneSetup(controller);
        if (authLigada) {
            builder.addFilters(new JwtAuthenticationFilter(jwtService, null, true, ""),
                    new ImpersonationFilter(personificacao));
        } else {
            builder.addFilters(new ImpersonationFilter(personificacao));
        }
        return builder.build();
    }

    private String token(String tenantId, String... roles) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("type", "access");
        claims.put("roles", List.of(roles));
        if (tenantId != null) {
            claims.put("tenantId", tenantId);
        }
        return jwtService.generateToken("user-1", "alice", 60_000, claims);
    }

    private static MockHttpServletRequestBuilder run(String tenantDoCorpo) {
        String tenant = tenantDoCorpo == null ? "" : ",\"tenantId\":\"" + tenantDoCorpo + "\"";
        return post("/api/orchestration/run").contentType(MediaType.APPLICATION_JSON)
                .content("{\"goal\":\"audit\"" + tenant + "}");
    }

    private String tenantQueRodou() {
        ArgumentCaptor<DynamicWorkflowRequest> enviado = ArgumentCaptor.forClass(DynamicWorkflowRequest.class);
        verify(service).run(enviado.capture());
        return enviado.getValue().tenantId();
    }

    @Test
    @DisplayName("token sem claim (o login de hoje) + corpo com outro tenant → 403 e nada roda")
    void tokenSemClaimNaoEscolhePeloCorpo() throws Exception {
        montar(true, false).perform(run("tenant-b").header("Authorization", "Bearer " + token(null, "EDITOR")))
                .andExpect(status().isForbidden());

        verify(service, never()).run(any());
    }

    @Test
    @DisplayName("nem o admin troca o tenant pelo corpo — para agir em outro tenant, personifica")
    void adminTambemNaoEscolhePeloCorpo() throws Exception {
        montar(true, false).perform(run("tenant-b").header("Authorization", "Bearer " + token("tenant-a", "ADMIN")))
                .andExpect(status().isForbidden());
        montar(true, false).perform(run("all").header("Authorization", "Bearer " + token("tenant-a", "ADMIN")))
                .andExpect(status().isForbidden());

        verify(service, never()).run(any());
    }

    @Test
    @DisplayName("corpo sem tenant ou com o próprio → roda no tenant da requisição")
    void proprioTenant() throws Exception {
        montar(true, false).perform(run("tenant-a").header("Authorization", "Bearer " + token("tenant-a", "VIEWER")))
                .andExpect(status().isOk());
        assertThat(tenantQueRodou()).isEqualTo("tenant-a");
    }

    @Test
    @DisplayName("token sem claim e corpo sem tenant → o tenant padrão, não um inventado")
    void tokenSemClaimRodaNoPadrao() throws Exception {
        montar(true, false).perform(run(null).header("Authorization", "Bearer " + token(null, "EDITOR")))
                .andExpect(status().isOk());
        assertThat(tenantQueRodou()).isEqualTo(TenantContext.DEFAULT_TENANT);
    }

    @Test
    @DisplayName("admin personificando (papel conferido no filtro) roda no tenant personificado")
    void personificacao() throws Exception {
        montar(true, true).perform(run("tenant-b")
                        .header("Authorization", "Bearer " + token("tenant-a", "superadmin"))
                        .header(ImpersonationFilter.HEADER_IMPERSONATE, "tenant-b"))
                .andExpect(status().isOk());
        assertThat(tenantQueRodou()).isEqualTo("tenant-b");
    }

    @Test
    @DisplayName("auth desligada (dev): o tenant do corpo vale, como antes")
    void authDesligada() throws Exception {
        montar(false, false).perform(run("tenant-b")).andExpect(status().isOk());
        assertThat(tenantQueRodou()).isEqualTo("tenant-b");
    }
}
