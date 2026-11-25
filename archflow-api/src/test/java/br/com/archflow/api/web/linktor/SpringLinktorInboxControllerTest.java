package br.com.archflow.api.web.linktor;

import br.com.archflow.api.linktor.LinktorFalso;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * O proxy do Inbox de ponta a ponta: a requisição da tela, o controller, o cliente real e o que o
 * Linktor falso recebeu (RCHFLW-112).
 */
@DisplayName("SpringLinktorInboxController — proxy do Inbox")
class SpringLinktorInboxControllerTest {

    private final ObjectMapper json = new ObjectMapper();
    private LinktorFalso linktor;
    private MockMvc mvc;

    @BeforeEach
    void subir() throws Exception {
        linktor = new LinktorFalso();
        mvc = MockMvcBuilders.standaloneSetup(new SpringLinktorInboxController(linktor.cliente())).build();
    }

    @AfterEach
    void descer() {
        linktor.close();
    }

    @Test
    @DisplayName("transferir chega ao Linktor como user_id")
    void transferirChegaComoUserId() throws Exception {
        linktor.responde("POST", "/conversations/conv-1/assign", 200,
                "{\"success\":true,\"data\":{\"id\":\"conv-1\",\"assigned_user_id\":\"u-1\"}}");

        MvcResult r = mvc.perform(post("/api/admin/linktor/inbox/conversations/conv-1/assign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u-1\"}"))
                .andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(linktor.recebidas()).singleElement().satisfies(req ->
                assertThat(json.readTree(req.corpo())).isEqualTo(json.readTree("{\"user_id\":\"u-1\"}")));
    }

    /** Não há "devolver à fila" no Linktor: vazio é recusado aqui, sem chamar o Linktor. */
    @Test
    @DisplayName("transferir sem operador é 400 e não chama o Linktor")
    void transferirSemOperador() throws Exception {
        MvcResult r = mvc.perform(post("/api/admin/linktor/inbox/conversations/conv-1/assign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"  \"}"))
                .andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(linktor.recebidas()).isEmpty();
    }

    @Test
    @DisplayName("GET /users devolve os usuários do Linktor")
    void listaUsuarios() throws Exception {
        linktor.responde("GET", "/users", 200, "{\"success\":true,\"data\":["
                + "{\"id\":\"u-1\",\"name\":\"Ana Operadora\",\"email\":\"ana@x.com\",\"role\":\"agent\"}]}");

        MvcResult r = mvc.perform(get("/api/admin/linktor/inbox/users")).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode corpo = json.readTree(r.getResponse().getContentAsString());
        assertThat(corpo.isArray()).isTrue();
        assertThat(corpo.get(0).get("id").asText()).isEqualTo("u-1");
        assertThat(corpo.get(0).get("name").asText()).isEqualTo("Ana Operadora");
    }

    /**
     * A chave de API tem papel {@code api}, e {@code /users} exige admin/owner. A recusa vira 502 com o
     * status do Linktor ao lado — repassada como 401/403, a UI do archflow a confundiria com a própria
     * sessão (o 401 faz logout).
     */
    @Test
    @DisplayName("Linktor recusando GET /users vira 502 com linktorStatus")
    void recusaDeUsuarios() throws Exception {
        linktor.responde("GET", "/users", 403,
                "{\"success\":false,\"error\":{\"code\":\"FORBIDDEN\",\"message\":\"insufficient permissions\"}}");

        MvcResult r = mvc.perform(get("/api/admin/linktor/inbox/users")).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(502);
        JsonNode corpo = json.readTree(r.getResponse().getContentAsString());
        assertThat(corpo.get("linktorStatus").asInt()).isEqualTo(403);
        assertThat(corpo.get("error").asText()).contains("/users").contains("403");
    }
}
