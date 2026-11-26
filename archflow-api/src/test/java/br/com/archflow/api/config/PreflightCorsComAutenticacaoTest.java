package br.com.archflow.api.config;

import br.com.archflow.security.jwt.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * QA2 T3: com a autenticação ligada, todo preflight CORS levava {@code 401 MISSING_TOKEN} do filtro
 * JWT, sem cabeçalho CORS — o navegador nunca manda {@code Authorization} num preflight, então
 * nenhum cliente de outra origem (o {@code <archflow-designer>} embutido, o Vite com auth ligada)
 * passava dele, embora o GET com token da mesma origem recebesse {@code Access-Control-Allow-Origin}.
 *
 * <p>Monta o caminho real do servidor: os filtros na ordem dele (JWT → tenant) na frente do
 * DispatcherServlet com o {@link WebMvcConfiguration} real (CORS de {@code archflow.cors.allowed-origins})
 * e os interceptors de papel do {@link AdminSecurityConfiguration}. A sonda conta as chamadas: um
 * preflight responde sem chegar a controller nenhum.
 */
@DisplayName("Preflight CORS com autenticação ligada")
class PreflightCorsComAutenticacaoTest {

    private static final String ORIGEM = "http://localhost:5173";
    private static final String SECRET = "test-secret-key-256-bits-long-enough-for-hs256-ok";

    /** Chamadas que chegaram à sonda — preflight nenhum pode somar aqui. */
    static final AtomicInteger CHAMADAS = new AtomicInteger();

    private final JwtService jwtService = new JwtService(SECRET);
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    @RestController
    static class Sonda {

        @RequestMapping("/api/workflows/{id}")
        Map<String, String> workflow(@PathVariable String id) {
            CHAMADAS.incrementAndGet();
            return Map.of("id", id);
        }

        @GetMapping("/api/admin/tenants")
        List<String> tenants() {
            CHAMADAS.incrementAndGet();
            return List.of("t1");
        }

        @PostMapping("/mcp")
        Map<String, String> mcp() {
            CHAMADAS.incrementAndGet();
            return Map.of("ok", "mcp");
        }

        @PostMapping("/api/agents/invoke")
        Map<String, String> invoke() {
            CHAMADAS.incrementAndGet();
            return Map.of("ok", "invoke");
        }
    }

    @EnableWebMvc
    @Configuration
    static class MvcComAuth {
        @Bean
        WebMvcConfiguration webMvcConfiguration() {
            return new WebMvcConfiguration(new String[] {ORIGEM}, new String[] {"classpath:/nao-existe/"});
        }

        @Bean
        AdminSecurityConfiguration adminSecurityConfiguration() {
            return new AdminSecurityConfiguration(true);
        }

        @Bean
        Sonda sonda() {
            return new Sonda();
        }
    }

    @BeforeEach
    void monta() {
        CHAMADAS.set(0);
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(MvcComAuth.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new JwtAuthenticationFilter(jwtService, null, null, true, ""),
                        new ImpersonationFilter(false))
                .build();
    }

    @AfterEach
    void desmonta() {
        context.close();
    }

    private static MockHttpServletRequestBuilder preflight(String path, String origem, String metodo) {
        return options(path)
                .header("Origin", origem)
                .header("Access-Control-Request-Method", metodo)
                .header("Access-Control-Request-Headers", "authorization,content-type");
    }

    private String accessToken() {
        return jwtService.generateToken("alice", "alice", 60_000,
                Map.of("type", "access", "roles", List.of("ADMIN")));
    }

    @Test
    @DisplayName("preflight de origem configurada para /api/** responde 200 com CORS, sem chegar ao controller")
    void preflightDaOrigemConfiguradaPassa() throws Exception {
        MvcResult res = mvc.perform(preflight("/api/workflows/x", ORIGEM, "PUT")).andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        assertThat(res.getResponse().getHeader("Access-Control-Allow-Origin")).isEqualTo(ORIGEM);
        assertThat(res.getResponse().getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
        assertThat(res.getResponse().getHeader("Access-Control-Allow-Methods")).contains("PUT");
        assertThat(res.getResponse().getHeader("Access-Control-Allow-Headers"))
                .containsIgnoringCase("authorization");
        assertThat(CHAMADAS).hasValue(0);
    }

    @Test
    @DisplayName("preflight de rota de admin passa o filtro e o interceptor de papel")
    void preflightDeAdminPassa() throws Exception {
        MvcResult res = mvc.perform(preflight("/api/admin/tenants", ORIGEM, "GET")).andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        assertThat(res.getResponse().getHeader("Access-Control-Allow-Origin")).isEqualTo(ORIGEM);
        assertThat(CHAMADAS).hasValue(0);
    }

    @Test
    @DisplayName("preflight de origem fora da lista é recusado pelo CORS (403), não liberado")
    void preflightDeOrigemNaoConfiguradaRecusado() throws Exception {
        MvcResult res = mvc.perform(preflight("/api/workflows/x", "https://outra.example", "PUT")).andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(403);
        assertThat(res.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
        assertThat(CHAMADAS).hasValue(0);
    }

    @Test
    @DisplayName("fora de /api/** (ex.: /mcp) não há CORS configurado: o preflight passa o filtro, sem liberar")
    void preflightSemMapeamentoCorsNaoLibera() throws Exception {
        MvcResult res = mvc.perform(preflight("/mcp", ORIGEM, "POST")).andReturn();

        assertThat(res.getResponse().getStatus()).isNotEqualTo(401);
        assertThat(res.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
        assertThat(CHAMADAS).hasValue(0);
    }

    @Test
    @DisplayName("OPTIONS que não é preflight (sem Access-Control-Request-Method) continua exigindo token")
    void optionsSemPreflightExigeToken() throws Exception {
        MvcResult res = mvc.perform(options("/api/workflows/x").header("Origin", ORIGEM)).andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(401);
        assertThat(res.getResponse().getContentAsString()).contains("MISSING_TOKEN");
        assertThat(CHAMADAS).hasValue(0);
    }

    @Test
    @DisplayName("a requisição de verdade depois do preflight continua exigindo token")
    void requisicaoCrossOriginSemTokenRecusada() throws Exception {
        MvcResult res = mvc.perform(put("/api/workflows/x").header("Origin", ORIGEM)).andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(401);
        assertThat(CHAMADAS).hasValue(0);
    }

    @Test
    @DisplayName("com token, a requisição cross-origin chega ao controller com Access-Control-Allow-Origin")
    void requisicaoCrossOriginComTokenPassa() throws Exception {
        MvcResult res = mvc.perform(get("/api/workflows/x")
                .header("Origin", ORIGEM)
                .header("Authorization", "Bearer " + accessToken())).andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        assertThat(res.getResponse().getHeader("Access-Control-Allow-Origin")).isEqualTo(ORIGEM);
        assertThat(CHAMADAS).hasValue(1);
    }

    @Test
    @DisplayName("VendaX Core em /api/agents/invoke (Bearer = chave de serviço, não JWT) chega ao controller")
    void clienteDeMaquinaNoInvokeSegue() throws Exception {
        MvcResult res = mvc.perform(post("/api/agents/invoke")
                .header("Authorization", "Bearer chave-de-servico-do-core")).andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        assertThat(CHAMADAS).hasValue(1);
    }
}
