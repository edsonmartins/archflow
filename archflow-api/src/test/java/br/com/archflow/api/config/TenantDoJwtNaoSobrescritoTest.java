package br.com.archflow.api.config;

import br.com.archflow.security.jwt.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RCHFLW-44: o {@code X-Tenant-Id} não sobrescreve o tenant do JWT.
 *
 * <p>Roda os dois filtros reais, na ordem do servidor (JWT → personificação), com tokens assinados
 * de verdade, e lê o tenant pelo mesmo caminho dos controllers ({@link TenantContext}). O defeito
 * era de composição — cada filtro isolado parecia certo e o segundo apagava o que o primeiro
 * gravara —, então só um teste dos dois juntos o pega.
 */
@DisplayName("Tenant do JWT não é sobrescrito pelo X-Tenant-Id")
class TenantDoJwtNaoSobrescritoTest {

    private static final String SECRET = "test-secret-key-256-bits-long-enough-for-hs256-ok";

    private final JwtService jwtService = new JwtService(SECRET);
    private final JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtService, true, "");
    private final ImpersonationFilter impersonationFilter = new ImpersonationFilter(true);

    /** Resultado de uma requisição pelos dois filtros. */
    private record Desfecho(int status, String tenant, String corpo, Object atributo) {
    }

    @AfterEach
    void limpa() {
        RequestContextHolder.resetRequestAttributes();
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

    private Desfecho requisicao(String token, String xTenantId, String xImpersonate) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/files");
        if (token != null) {
            req.addHeader("Authorization", "Bearer " + token);
        }
        if (xTenantId != null) {
            req.addHeader(ImpersonationFilter.HEADER_TENANT, xTenantId);
        }
        if (xImpersonate != null) {
            req.addHeader(ImpersonationFilter.HEADER_IMPERSONATE, xImpersonate);
        }
        MockHttpServletResponse resp = new MockHttpServletResponse();
        AtomicReference<String> visto = new AtomicReference<>();
        AtomicReference<Object> atributo = new AtomicReference<>();
        jwtFilter.doFilter(req, resp, (rq, rs) -> impersonationFilter.doFilter(rq, rs, (rq2, rs2) -> {
            // O que o controller enxergaria — pelos dois caminhos: TenantContext e o atributo cru
            // (@RequestAttribute do ingest, workspace, conversa).
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
            visto.set(TenantContext.currentTenantId());
            atributo.set(req.getAttribute(ImpersonationFilter.ATTR_TENANT_ID));
        }));
        return new Desfecho(resp.getStatus(), visto.get(), resp.getContentAsString(), atributo.get());
    }

    @Test
    @DisplayName("token do tenant A + X-Tenant-Id B → 403, e o controller nunca roda")
    void headerDeOutroTenantERecusado() throws Exception {
        Desfecho d = requisicao(token("tenant-a", "editor"), "tenant-b", null);

        assertThat(d.status()).isEqualTo(403);
        assertThat(d.tenant()).as("a requisição não pode chegar ao controller").isNull();
        assertThat(d.corpo()).contains("TENANT_MISMATCH");
    }

    @Test
    @DisplayName("token do tenant A + X-Tenant-Id A → segue no tenant A")
    void headerQueRepeteAClaimPassa() throws Exception {
        Desfecho d = requisicao(token("tenant-a", "editor"), "tenant-a", null);

        assertThat(d.status()).isEqualTo(200);
        assertThat(d.tenant()).isEqualTo("tenant-a");
    }

    @Test
    @DisplayName("token do tenant A sem header → tenant A")
    void semHeaderValeAClaim() throws Exception {
        assertThat(requisicao(token("tenant-a", "editor"), null, null).tenant()).isEqualTo("tenant-a");
    }

    /**
     * O login de hoje não põe a claim no token. Se "sem claim" deixasse o header livre, a correção
     * não valeria para nenhum usuário real.
     */
    @Test
    @DisplayName("token sem claim de tenant é do tenant padrão: X-Tenant-Id de outro tenant → 403")
    void tokenSemClaimNaoDeixaOHeaderEscolher() throws Exception {
        Desfecho recusado = requisicao(token(null, "editor"), "tenant-b", null);
        Desfecho semHeader = requisicao(token(null, "editor"), null, null);
        Desfecho repetindo = requisicao(token(null, "editor"), TenantContext.DEFAULT_TENANT, null);

        assertThat(recusado.status()).isEqualTo(403);
        assertThat(semHeader.tenant()).isEqualTo("__default__");
        assertThat(repetindo.tenant()).isEqualTo("__default__");
    }

    /**
     * Quem lê o atributo direto (o ingest de eventos fixa o tenant por ele) não pode ver {@code null}
     * onde o {@link TenantContext} vê {@code __default__}: com null o ingest não fixava tenant e cada
     * evento ficava com o tenant escrito no próprio payload.
     */
    @Test
    @DisplayName("token sem claim grava __default__ no atributo, não o deixa vazio")
    void tokenSemClaimGravaOTenantPadraoNoAtributo() throws Exception {
        assertThat(requisicao(token(null, "editor"), null, null).atributo()).isEqualTo("__default__");
        assertThat(requisicao(token(null, "editor"), TenantContext.DEFAULT_TENANT, null).atributo())
                .isEqualTo("__default__");
        assertThat(requisicao(token("tenant-a", "editor"), null, null).atributo()).isEqualTo("tenant-a");
    }

    @Test
    @DisplayName("personificação verificada (papel superadmin) continua sendo o caminho para outro tenant")
    void superadminPersonificaOutroTenant() throws Exception {
        Desfecho d = requisicao(token("tenant-a", "superadmin"), null, "tenant-b");

        assertThat(d.status()).isEqualTo(200);
        assertThat(d.tenant()).isEqualTo("tenant-b");
    }

    @Test
    @DisplayName("personificação sem o papel é recusada (403) — não troca o tenant nem responde com o do token")
    void personificacaoSemPapelNaoTrocaTenant() throws Exception {
        // RCHFLW-193: antes era ignorada em silêncio (200 com os dados do tenant-a, lidos como do tenant-b).
        Desfecho d = requisicao(token("tenant-a", "editor"), null, "tenant-b");

        assertThat(d.status()).isEqualTo(403);
        assertThat(d.tenant()).isNotEqualTo("tenant-b");
        assertThat(d.corpo()).contains("IMPERSONATION_DENIED");
    }

    @Test
    @DisplayName("sem JWT (auth desligada em dev), o X-Tenant-Id ainda define o tenant")
    void semJwtOHeaderVale() throws Exception {
        JwtAuthenticationFilter desligado = new JwtAuthenticationFilter(jwtService, false, "");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/files");
        req.addHeader(ImpersonationFilter.HEADER_TENANT, "tenant-b");
        AtomicReference<String> visto = new AtomicReference<>();
        desligado.doFilter(req, new MockHttpServletResponse(),
                (rq, rs) -> impersonationFilter.doFilter(rq, rs, (rq2, rs2) -> {
                    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
                    visto.set(TenantContext.currentTenantId());
                }));

        assertThat(visto.get()).isEqualTo("tenant-b");
    }
}
