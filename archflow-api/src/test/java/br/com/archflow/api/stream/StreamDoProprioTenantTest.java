package br.com.archflow.api.stream;

import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.api.config.ImpersonationFilter;
import br.com.archflow.api.config.JwtAuthenticationFilter;
import br.com.archflow.security.jwt.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RCHFLW-49: o stream SSE só abre o canal do tenant da própria requisição.
 *
 * <p>Roda os filtros reais na ordem do servidor (JWT → personificação) na frente do controller, com
 * tokens assinados de verdade: o tenant efetivo é decidido pelos filtros e conferido pelo controller,
 * e o defeito estava justamente na falta dessa conferência. A prova de que nada vazou é o registry —
 * uma recusa que ainda registrasse o emitter continuaria entregando os eventos.
 */
@DisplayName("Stream SSE só do próprio tenant")
class StreamDoProprioTenantTest {

    private static final String SECRET = "test-secret-key-256-bits-long-enough-for-hs256-ok";

    private final JwtService jwtService = new JwtService(SECRET);
    private EventStreamRegistry registry;
    private MockMvc mvc;

    @BeforeEach
    void monta() {
        registry = new EventStreamRegistry();
        mvc = MockMvcBuilders.standaloneSetup(new SpringStreamController(registry))
                .addFilters(new JwtAuthenticationFilter(jwtService, null, true, ""),
                        new ImpersonationFilter(true))
                .build();
    }

    @AfterEach
    void desmonta() {
        registry.shutdown();
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

    private ResultActions assina(String path, String token, String impersonate) throws Exception {
        MockHttpServletRequestBuilder req = get(path).header("Authorization", "Bearer " + token);
        if (impersonate != null) {
            req.header(ImpersonationFilter.HEADER_IMPERSONATE, impersonate);
        }
        return mvc.perform(req);
    }

    private int assinantes(String canal) {
        return registry.getEmitters(canal).size();
    }

    @Test
    @DisplayName("token do tenant A assinando o canal do tenant B → 403 e nenhum emitter registrado")
    void outroTenantERecusado() throws Exception {
        assina("/api/stream/tenant-b/conv-1", token("tenant-a", "editor"), null)
                .andExpect(status().isForbidden());

        assertThat(assinantes("tenant-b:conv-1")).isZero();
    }

    @Test
    @DisplayName("token do tenant A assinando o próprio canal → stream aberto em tenant-a:conv-1")
    void proprioTenantAbre() throws Exception {
        assina("/api/stream/tenant-a/conv-1", token("tenant-a", "editor"), null)
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());

        assertThat(assinantes("tenant-a:conv-1")).isEqualTo(1);
    }

    /**
     * A variante do canal administrativo: {@code /__admin__/<tenant B>} caía em
     * {@code __admin__:<tenant B>} e entregava os eventos de execução de B a qualquer usuário.
     */
    @Test
    @DisplayName("canal administrativo: só o do próprio tenant")
    void canalAdminSoDoProprioTenant() throws Exception {
        String tokenA = token("tenant-a", "editor");

        assina("/api/stream/__admin__/tenant-b", tokenA, null).andExpect(status().isForbidden());
        assina("/api/stream/__admin__/tenant-a", tokenA, null).andExpect(status().isOk());

        assertThat(assinantes("__admin__:tenant-b")).isZero();
        assertThat(assinantes("__admin__:tenant-a")).isEqualTo(1);
    }

    @Test
    @DisplayName("superadmin personificando o tenant B (papel conferido no servidor) assina o canal de B")
    void superadminPersonificandoAssinaOOutroTenant() throws Exception {
        assina("/api/stream/tenant-b/conv-1", token("tenant-a", "superadmin"), "tenant-b")
                .andExpect(status().isOk());

        assertThat(assinantes("tenant-b:conv-1")).isEqualTo(1);
    }

    @Test
    @DisplayName("personificação sem o papel não abre o canal de outro tenant")
    void personificacaoSemPapelERecusada() throws Exception {
        assina("/api/stream/tenant-b/conv-1", token("tenant-a", "editor"), "tenant-b")
                .andExpect(status().isForbidden());

        assertThat(assinantes("tenant-b:conv-1")).isZero();
    }

    /**
     * Personificar o id reservado não é atalho para o canal administrativo de todos: com a regra
     * geral (caminho == tenant efetivo) sozinha, {@code /__admin__/<B>} passaria.
     */
    @Test
    @DisplayName("personificar \"__admin__\" não abre o canal administrativo de outro tenant")
    void personificarOIdReservadoNaoAbreOAdminDeOutro() throws Exception {
        assina("/api/stream/__admin__/tenant-b", token("tenant-a", "superadmin"), "__admin__")
                .andExpect(status().isForbidden());

        assertThat(assinantes("__admin__:tenant-b")).isZero();
    }

    /**
     * A mesma brecha pela rota sem tenant: com o tenant efetivo igual ao id reservado,
     * {@code /api/stream/<B>} abria {@code __admin__:<B>} — e o log de auditoria registrava a
     * personificação como {@code "__admin__"}, não como B.
     */
    @Test
    @DisplayName("rota sem tenant com o id reservado como tenant efetivo não abre o admin de outro")
    void rotaSemTenantComIdReservadoNaoAbreOAdminDeOutro() throws Exception {
        assina("/api/stream/tenant-b", token("tenant-a", "superadmin"), "__admin__")
                .andExpect(status().isForbidden());

        assertThat(assinantes("__admin__:tenant-b")).isZero();
    }

    /**
     * Com a auth desligada não há JWT, e o {@code X-Tenant-Id} vira o tenant efetivo — inclusive o
     * id reservado. As duas rotas têm de recusar.
     */
    @Test
    @DisplayName("auth desligada: X-Tenant-Id \"__admin__\" não abre o admin de outro tenant")
    void authDesligadaComIdReservadoNaoAbreOAdminDeOutro() throws Exception {
        MockMvc semAuth = MockMvcBuilders.standaloneSetup(new SpringStreamController(registry))
                .addFilters(new ImpersonationFilter(true))
                .build();

        semAuth.perform(get("/api/stream/tenant-b").header(ImpersonationFilter.HEADER_TENANT, "__admin__"))
                .andExpect(status().isForbidden());
        semAuth.perform(get("/api/stream/__admin__/tenant-b").header(ImpersonationFilter.HEADER_TENANT, "__admin__"))
                .andExpect(status().isForbidden());

        assertThat(assinantes("__admin__:tenant-b")).isZero();
    }

    /**
     * O login de hoje não põe a claim no token: o tenant desse usuário é o canônico
     * {@code __default__}. O {@code "default"} que a UI inventava deixa de ser aceito — era o canal
     * de fallback em que a resposta do chat de qualquer tenant podia cair.
     */
    @Test
    @DisplayName("token sem claim: só o __default__; o \"default\" legado é recusado")
    void tokenSemClaimSoNoTenantPadrao() throws Exception {
        String semClaim = token(null, "editor");

        assina("/api/stream/__default__/conv-1", semClaim, null).andExpect(status().isOk());
        assina("/api/stream/default/conv-1", semClaim, null).andExpect(status().isForbidden());

        assertThat(assinantes("__default__:conv-1")).isEqualTo(1);
        assertThat(assinantes("default:conv-1")).isZero();
    }

    @Test
    @DisplayName("GET /api/stream/{sessionId}: o servidor usa o tenant efetivo")
    void rotaSemTenantUsaOTenantDaRequisicao() throws Exception {
        assina("/api/stream/conv-1", token("tenant-a", "editor"), null).andExpect(status().isOk());
        assina("/api/stream/conv-2", token(null, "editor"), null).andExpect(status().isOk());
        assina("/api/stream/conv-3", token("tenant-a", "superadmin"), "tenant-b").andExpect(status().isOk());

        assertThat(assinantes("tenant-a:conv-1")).isEqualTo(1);
        assertThat(assinantes("__default__:conv-2")).isEqualTo(1);
        assertThat(assinantes("tenant-b:conv-3")).isEqualTo(1);
    }
}
