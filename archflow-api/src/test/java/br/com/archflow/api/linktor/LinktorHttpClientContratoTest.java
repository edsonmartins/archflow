package br.com.archflow.api.linktor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O corpo que o {@link LinktorHttpClient} manda é o que os handlers do Linktor leem
 * ({@code internal/api/handlers}, tags JSON), lido cru no servidor falso (RCHFLW-112).
 *
 * <p>A transferência mandava {@code {"userId": …}}; o Linktor exige {@code user_id}
 * ({@code AssignRequest}, {@code binding:"required"}) e respondia 400 a toda transferência.</p>
 */
@DisplayName("LinktorHttpClient — contrato com o Linktor")
class LinktorHttpClientContratoTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private LinktorFalso linktor;
    private LinktorHttpClient client;

    @BeforeEach
    void subir() throws Exception {
        linktor = new LinktorFalso();
        client = linktor.cliente();
    }

    @AfterEach
    void descer() {
        linktor.close();
    }

    private LinktorFalso.Requisicao unica() {
        assertThat(linktor.recebidas()).hasSize(1);
        return linktor.recebidas().get(0);
    }

    @Test
    @DisplayName("assign manda exatamente {\"user_id\": …}")
    void assignMandaUserId() throws Exception {
        linktor.responde("POST", "/conversations/conv-1/assign", 200,
                "{\"success\":true,\"data\":{\"id\":\"conv-1\",\"assigned_user_id\":\"u-1\"}}");

        Map<String, Object> conversa = client.assignConversation("conv-1", "u-1");

        LinktorFalso.Requisicao r = unica();
        assertThat(r.metodo()).isEqualTo("POST");
        assertThat(r.caminho()).isEqualTo("/conversations/conv-1/assign");
        assertThat(JSON.readTree(r.corpo())).isEqualTo(JSON.readTree("{\"user_id\":\"u-1\"}"));
        assertThat(r.chaveDeApi()).isEqualTo(LinktorFalso.CHAVE);
        // Sem o envelope: quem chama recebe a conversa, como nas listas.
        assertThat(conversa).containsEntry("assigned_user_id", "u-1");
    }

    @Test
    @DisplayName("enviar mensagem manda exatamente {content, content_type}")
    void enviarMensagemMandaContentType() throws Exception {
        linktor.responde("POST", "/conversations/conv-1/messages", 201,
                "{\"success\":true,\"data\":{\"id\":\"m-1\",\"sender_type\":\"user\"}}");

        client.sendMessage("conv-1", "Olá!");

        assertThat(JSON.readTree(unica().corpo()))
                .isEqualTo(JSON.readTree("{\"content\":\"Olá!\",\"content_type\":\"text\"}"));
    }

    @Test
    @DisplayName("usuários vêm de GET /users com o page_size máximo do Linktor, sem o envelope")
    void listaUsuarios() throws Exception {
        linktor.responde("GET", "/users", 200, "{\"success\":true,\"data\":["
                + "{\"id\":\"u-1\",\"name\":\"Ana Operadora\",\"email\":\"ana@x.com\",\"role\":\"agent\","
                + "\"status\":\"active\"}],\"meta\":{\"page\":1,\"page_size\":250,\"total_items\":1}}");

        List<Map<String, Object>> usuarios = client.listUsers();

        assertThat(unica().caminho()).isEqualTo("/users?page_size=250");
        assertThat(usuarios).singleElement().satisfies(u -> {
            assertThat(u).containsEntry("id", "u-1");
            assertThat(u).containsEntry("name", "Ana Operadora");
        });
    }

    @Test
    @DisplayName("a recusa do Linktor carrega o status — 403 da chave de API em GET /users")
    void recusaCarregaStatus() {
        linktor.responde("GET", "/users", 403,
                "{\"success\":false,\"error\":{\"code\":\"FORBIDDEN\",\"message\":\"insufficient permissions\"}}");

        assertThatThrownBy(client::listUsers)
                .isInstanceOfSatisfying(LinktorHttpException.class, e -> assertThat(e.status()).isEqualTo(403))
                .hasMessageContaining("HTTP 403");
    }

    @Test
    @DisplayName("o detalhe da conversa chega sem o envelope {success, data}")
    void detalheSemEnvelope() throws Exception {
        linktor.responde("GET", "/conversations/conv-1", 200, "{\"success\":true,\"data\":"
                + "{\"id\":\"conv-1\",\"status\":\"open\",\"contact\":{\"name\":\"Maria Souza\"}}}");

        Map<String, Object> conversa = client.getConversation("conv-1");

        assertThat(conversa).containsEntry("status", "open").doesNotContainKey("success");
    }
}
