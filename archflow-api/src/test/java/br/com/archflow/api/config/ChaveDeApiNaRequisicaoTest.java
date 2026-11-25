package br.com.archflow.api.config;

import br.com.archflow.api.apikey.impl.ApiKeyControllerImpl;
import br.com.archflow.api.web.apikey.SpringApiKeyController;
import br.com.archflow.model.security.ApiKeyScope;
import br.com.archflow.model.security.Role;
import br.com.archflow.model.security.User;
import br.com.archflow.security.apikey.ApiKeyService;
import br.com.archflow.security.auth.InMemoryUserRepository;
import br.com.archflow.security.jwt.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RCHFLW-83/84/86: a chave de API é criada com os escopos do backend, pertence ao usuário autenticado
 * e autentica a requisição no mesmo {@code Authorization: Bearer} que o Publicar ensina.
 *
 * <p>Roda os filtros reais na ordem do servidor (JWT/chave → tenant) na frente do
 * {@link SpringApiKeyController} real e de um controller-sonda nos caminhos de execução, com o
 * {@link ApiKeyService} real. A sonda devolve a identidade que a requisição recebeu — usuário e
 * tenant —, que é o que os controllers de verdade leem.
 */
@DisplayName("Chave de API: criação, dono e autenticação da requisição")
class ChaveDeApiNaRequisicaoTest {

    private static final String SECRET = "test-secret-key-256-bits-long-enough-for-hs256-ok";

    private final JwtService jwtService = new JwtService(SECRET);
    private final ObjectMapper json = new ObjectMapper();
    private ApiKeyService apiKeys;
    private InMemoryUserRepository users;

    /** Responde com a identidade que os filtros deram à requisição. */
    @RestController
    static class Sonda {

        private static Map<String, Object> identidade(HttpServletRequest http) {
            Map<String, Object> body = new HashMap<>();
            body.put("userId", String.valueOf(http.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)));
            body.put("tenantId", TenantContext.currentTenantId());
            return body;
        }

        @PostMapping("/api/workflows/{id}/execute")
        Map<String, Object> executa(@PathVariable String id, HttpServletRequest http) {
            return identidade(http);
        }

        @GetMapping("/api/executions/{id}")
        Map<String, Object> execucao(@PathVariable String id, HttpServletRequest http) {
            return identidade(http);
        }
    }

    @BeforeEach
    void monta() {
        users = new InMemoryUserRepository();
        usuario("alice", Role.createAdminRole());
        usuario("bob", Role.createAdminRole());
        apiKeys = new ApiKeyService(new InMemoryApiKeyRepository(), users);
    }

    /** Usuário do login, com o id que vai no subject do token. */
    private User usuario(String id, Role role) {
        User user = new User();
        user.setId(id);
        user.setUsername(id);
        user.setEmail(id + "@example.com");
        user.setEnabled(true);
        user.addRole(role);
        return users.save(user);
    }

    private MockMvc mvc(boolean authEnabled) {
        return MockMvcBuilders
                .standaloneSetup(new SpringApiKeyController(new ApiKeyControllerImpl(apiKeys)), new Sonda())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new JwtAuthenticationFilter(jwtService, null, apiKeys, authEnabled, ""),
                        new ImpersonationFilter(false))
                .build();
    }

    private String jwt(String userId, String tenantId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("type", "access");
        claims.put("roles", List.of("ADMIN"));
        claims.put("tenantId", tenantId);
        return jwtService.generateToken(userId, userId, 60_000, claims);
    }

    /** Cria a chave pela API, como a UI faz, e devolve a resposta. */
    private JsonNode criaPelaApi(MockMvc mvc, String bearer, String extraHeader, String... scopes) throws Exception {
        var req = post("/api/apikeys")
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "CI", "scopes", List.of(scopes))));
        if (extraHeader != null) {
            req.header("X-User-Id", extraHeader);
        }
        String body = mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    /** O campo do corpo JSON, por JSON Pointer (sem hamcrest no classpath de teste). */
    private ResultMatcher campo(String pointer, Object esperado) {
        return r -> assertThat(json.readTree(r.getResponse().getContentAsString()).at(pointer).asText())
                .as(pointer).isEqualTo(String.valueOf(esperado));
    }

    private ResultMatcher tamanho(int esperado) {
        return r -> assertThat(json.readTree(r.getResponse().getContentAsString()).size()).isEqualTo(esperado);
    }

    private String credencial(String owner, String tenant, Set<ApiKeyScope> scopes) {
        return apiKeys.createApiKey(tenant, owner, "k", scopes, null).credential();
    }

    @Nested
    @DisplayName("criação e dono (RCHFLW-83/86)")
    class CriacaoEDono {

        @Test
        @DisplayName("GET /api/apikeys/scopes publica o vocabulário que a criação aceita")
        void publicaOsEscopos() throws Exception {
            mvc(true).perform(get("/api/apikeys/scopes").header("Authorization", "Bearer " + jwt("alice", "t-a")))
                    .andExpect(status().isOk())
                    .andExpect(campo("/0/name", ApiKeyScope.values()[0].name()))
                    .andExpect(tamanho(ApiKeyScope.values().length));
        }

        @Test
        @DisplayName("cria com os escopos do backend; dono e tenant vêm do token, X-User-Id é ignorado")
        void criaComEscoposDoBackendEDonoDoToken() throws Exception {
            MockMvc mvc = mvc(true);
            JsonNode criada = criaPelaApi(mvc, jwt("alice", "t-a"), "mallory", "WORKFLOW_EXECUTE");

            assertThat(criada.get("apiKey").asText()).startsWith(criada.get("keyId").asText() + ".");
            assertThat(criada.get("scopes").get(0).asText()).isEqualTo("WORKFLOW_EXECUTE");
            var chave = apiKeys.authenticate(criada.get("apiKey").asText()).apiKey();
            assertThat(chave.getOwnerId()).isEqualTo("alice");
            assertThat(chave.getTenantId()).isEqualTo("t-a");
        }

        @Test
        @DisplayName("escopo no formato antigo (workflow.read) → 400 com mensagem legível, nenhuma chave")
        void escopoForaDoVocabularioERecusado() throws Exception {
            mvc(true).perform(post("/api/apikeys")
                            .header("Authorization", "Bearer " + jwt("alice", "t-a"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"CI\",\"scopes\":[\"workflow.read\"]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(r -> assertThat(json.readTree(r.getResponse().getContentAsString())
                            .get("error").asText()).startsWith("Unknown scope 'workflow.read'. Valid scopes: "));
            assertThat(apiKeys.listApiKeys("t-a", "alice")).isEmpty();
        }

        @Test
        @DisplayName("X-User-Id de outro usuário não lista nem revoga as chaves dele")
        void cabecalhoDeUsuarioNaoEscolheODono() throws Exception {
            MockMvc mvc = mvc(true);
            // A UI antiga mandava o próprio id no cabeçalho; o de outro usuário escolhia de quem eram as chaves.
            JsonNode daAlice = criaPelaApi(mvc, jwt("alice", "t-a"), "alice", "WORKFLOW_EXECUTE");
            String keyId = daAlice.get("keyId").asText();
            String bob = jwt("bob", "t-a");

            mvc.perform(get("/api/apikeys").header("Authorization", "Bearer " + bob).header("X-User-Id", "alice"))
                    .andExpect(status().isOk())
                    .andExpect(tamanho(0));
            mvc.perform(delete("/api/apikeys/" + keyId)
                            .header("Authorization", "Bearer " + bob).header("X-User-Id", "alice"))
                    .andExpect(status().isNotFound());

            assertThat(apiKeys.authenticate(daAlice.get("apiKey").asText()).apiKey().isEnabled()).isTrue();
            mvc.perform(get("/api/apikeys").header("Authorization", "Bearer " + jwt("alice", "t-a")))
                    .andExpect(tamanho(1))
                    .andExpect(campo("/0/keyId", keyId));
        }

        @Test
        @DisplayName("sem usuário autenticado não há dono: 401 legível, mesmo com X-User-Id")
        void semPrincipalNaoCria() throws Exception {
            mvc(false).perform(post("/api/apikeys").header("X-User-Id", "alice")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"CI\",\"scopes\":[\"WORKFLOW_EXECUTE\"]}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(campo("/message", "API key management requires an authenticated user"));
            assertThat(apiKeys.listApiKeys(TenantContext.DEFAULT_TENANT, "alice")).isEmpty();
        }

        @Test
        @DisplayName("escopo além do papel de quem cria → 403 legível, nenhuma chave; /scopes já o marca")
        void escopoAlemDoPapelERecusado() throws Exception {
            usuario("vera", Role.createViewerRole());
            MockMvc mvc = mvc(true);
            String vera = jwt("vera", "t-a");

            mvc.perform(post("/api/apikeys")
                            .header("Authorization", "Bearer " + vera)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"CI\",\"scopes\":[\"WORKFLOW_DELETE\"]}"))
                    .andExpect(status().isForbidden())
                    .andExpect(campo("/message", "Your role does not grant the scope(s) WORKFLOW_DELETE; "
                            + "an API key cannot exceed the permissions of its owner"));
            assertThat(apiKeys.listApiKeys("t-a", "vera")).isEmpty();

            String body = mvc.perform(get("/api/apikeys/scopes").header("Authorization", "Bearer " + vera))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            Map<String, Boolean> concede = new HashMap<>();
            json.readTree(body).forEach(n -> concede.put(n.get("name").asText(), n.get("grantable").asBoolean()));
            assertThat(concede).containsEntry("WORKFLOW_READ", true).containsEntry("WORKFLOW_DELETE", false)
                    .containsEntry("ADMIN", false);
        }

        @Test
        @DisplayName("revogar pela API invalida a chave na próxima requisição (401)")
        void revogarInvalida() throws Exception {
            MockMvc mvc = mvc(true);
            String alice = jwt("alice", "t-a");
            JsonNode criada = criaPelaApi(mvc, alice, null, "WORKFLOW_EXECUTE");
            String chave = criada.get("apiKey").asText();
            mvc.perform(post("/api/workflows/wf-1/execute").header("Authorization", "Bearer " + chave))
                    .andExpect(status().isOk());

            mvc.perform(delete("/api/apikeys/" + criada.get("keyId").asText())
                            .header("Authorization", "Bearer " + alice))
                    .andExpect(status().isNoContent());

            mvc.perform(post("/api/workflows/wf-1/execute").header("Authorization", "Bearer " + chave))
                    .andExpect(status().isUnauthorized())
                    .andExpect(campo("/error", "INVALID_API_KEY"))
                    .andExpect(campo("/message", "API key has been revoked"));
        }
    }

    @Nested
    @DisplayName("autenticação pela chave (RCHFLW-84)")
    class Autenticacao {

        @Test
        @DisplayName("chave válida com o escopo: a identidade é o dono e o tenant da chave")
        void chaveValidaAutentica() throws Exception {
            String chave = credencial("alice", "t-a", Set.of(ApiKeyScope.WORKFLOW_EXECUTE));

            mvc(true).perform(post("/api/workflows/wf-1/execute").header("Authorization", "Bearer " + chave))
                    .andExpect(status().isOk())
                    .andExpect(campo("/userId", "alice"))
                    .andExpect(campo("/tenantId", "t-a"));
        }

        @Test
        @DisplayName("o tenant é o da chave: X-Tenant-Id diferente → 403 TENANT_MISMATCH")
        void tenantDaChaveNaoESobrescrito() throws Exception {
            String chave = credencial("alice", "t-a", Set.of(ApiKeyScope.WORKFLOW_EXECUTE));

            mvc(true).perform(post("/api/workflows/wf-1/execute")
                            .header("Authorization", "Bearer " + chave).header("X-Tenant-Id", "t-b"))
                    .andExpect(status().isForbidden())
                    .andExpect(campo("/error", "TENANT_MISMATCH"));
        }

        @Test
        @DisplayName("revogada, expirada, desconhecida ou com segredo errado → 401")
        void chaveInvalidaE401() throws Exception {
            MockMvc mvc = mvc(true);
            var revogada = apiKeys.createApiKey("t-a", "alice", "r", Set.of(ApiKeyScope.WORKFLOW_EXECUTE), null);
            apiKeys.revokeApiKey("t-a", "alice", revogada.apiKey().getKeyId());
            String expirada = apiKeys.createApiKey("t-a", "alice", "e", Set.of(ApiKeyScope.WORKFLOW_EXECUTE),
                    LocalDateTime.now().minusSeconds(1)).credential();
            String valida = credencial("alice", "t-a", Set.of(ApiKeyScope.WORKFLOW_EXECUTE));

            Map<String, String> casos = Map.of(
                    revogada.credential(), "API key has been revoked",
                    expirada, "API key has expired",
                    "ak_desconhecida.segredo", "Invalid API key",
                    valida.substring(0, valida.indexOf('.')) + ".errado", "Invalid API key");
            for (var caso : casos.entrySet()) {
                mvc.perform(post("/api/workflows/wf-1/execute").header("Authorization", "Bearer " + caso.getKey()))
                        .andExpect(status().isUnauthorized())
                        .andExpect(campo("/error", "INVALID_API_KEY"))
                        .andExpect(campo("/message", caso.getValue()));
            }
        }

        @Test
        @DisplayName("sem o escopo exigido → 403 legível")
        void semEscopoE403() throws Exception {
            String chave = credencial("alice", "t-a", Set.of(ApiKeyScope.WORKFLOW_EXECUTE));

            mvc(true).perform(get("/api/executions/exec-1").header("Authorization", "Bearer " + chave))
                    .andExpect(status().isForbidden())
                    .andExpect(campo("/error", "INSUFFICIENT_SCOPE"))
                    .andExpect(r -> assertThat(json.readTree(r.getResponse().getContentAsString())
                            .get("message").asText()).contains("EXECUTION_READ"));
        }

        @Test
        @DisplayName("endpoint que nenhum escopo cobre (gestão de chaves) → 403, mesmo com ADMIN")
        void endpointForaDosEscoposE403() throws Exception {
            String admin = credencial("alice", "t-a", Set.of(ApiKeyScope.ADMIN));

            mvc(true).perform(get("/api/apikeys").header("Authorization", "Bearer " + admin))
                    .andExpect(status().isForbidden())
                    .andExpect(campo("/error", "API_KEY_ENDPOINT_NOT_ALLOWED"));
            mvc(true).perform(get("/api/executions/exec-1").header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("com a autenticação desligada, a chave válida continua limitada aos escopos dela")
        void escopoValeComAuthDesligada() throws Exception {
            MockMvc mvc = mvc(false);
            String chave = credencial("alice", "t-a", Set.of(ApiKeyScope.WORKFLOW_EXECUTE));

            mvc.perform(get("/api/apikeys").header("Authorization", "Bearer " + chave))
                    .andExpect(status().isForbidden());
            // Chave inválida com auth desligada: segue anônima, como um JWT inválido.
            mvc.perform(post("/api/workflows/wf-1/execute").header("Authorization", "Bearer ak_x.y"))
                    .andExpect(status().isOk())
                    .andExpect(campo("/userId", "null"));
        }

        @Test
        @DisplayName("dono desativado ou removido: a chave dele para na hora (401)")
        void donoDesativadoOuRemovidoCortaAChave() throws Exception {
            MockMvc mvc = mvc(true);
            String daAlice = credencial("alice", "t-a", Set.of(ApiKeyScope.WORKFLOW_EXECUTE));
            String doBob = credencial("bob", "t-a", Set.of(ApiKeyScope.WORKFLOW_EXECUTE));

            users.findById("alice").orElseThrow().setEnabled(false);
            users.delete(users.findById("bob").orElseThrow());

            for (String chave : List.of(daAlice, doBob)) {
                mvc.perform(post("/api/workflows/wf-1/execute").header("Authorization", "Bearer " + chave))
                        .andExpect(status().isUnauthorized())
                        .andExpect(campo("/error", "INVALID_API_KEY"))
                        .andExpect(campo("/message", "The owner of this API key is disabled or no longer exists"));
            }
        }

        @Test
        @DisplayName("papel do dono rebaixado: o escopo que o papel não concede mais → 403")
        void papelRebaixadoLimitaAChave() throws Exception {
            User dani = usuario("dani", Role.createDesignerRole());
            String chave = credencial("dani", "t-a", Set.of(ApiKeyScope.WORKFLOW_EXECUTE));
            MockMvc mvc = mvc(true);
            mvc.perform(post("/api/workflows/wf-1/execute").header("Authorization", "Bearer " + chave))
                    .andExpect(status().isOk());

            dani.getRoles().clear();
            dani.addRole(Role.createViewerRole());

            mvc.perform(post("/api/workflows/wf-1/execute").header("Authorization", "Bearer " + chave))
                    .andExpect(status().isForbidden())
                    .andExpect(campo("/error", "INSUFFICIENT_OWNER_PERMISSION"));
        }

        @Test
        @DisplayName("JWT segue como antes")
        void jwtInalterado() throws Exception {
            mvc(true).perform(get("/api/executions/exec-1").header("Authorization", "Bearer " + jwt("bob", "t-b")))
                    .andExpect(status().isOk())
                    .andExpect(campo("/userId", "bob"))
                    .andExpect(campo("/tenantId", "t-b"));
            mvc(true).perform(get("/api/executions/exec-1").header("Authorization", "Bearer eyJ.invalido.x"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(campo("/error", "INVALID_TOKEN"));
        }
    }
}
