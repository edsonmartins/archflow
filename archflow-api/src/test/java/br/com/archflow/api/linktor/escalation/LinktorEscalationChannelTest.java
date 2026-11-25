package br.com.archflow.api.linktor.escalation;

import br.com.archflow.api.linktor.LinktorFalso;
import br.com.archflow.model.escalation.EscalationException;
import br.com.archflow.model.escalation.EscalationRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O Linktor atribui a um usuário e não tem "devolver à fila" (RCHFLW-112): o canal mandava um id vazio
 * para isso, e o Linktor respondia 400.
 */
@DisplayName("LinktorEscalationChannel")
class LinktorEscalationChannelTest {

    private final ObjectMapper json = new ObjectMapper();
    private LinktorFalso linktor;
    private LinktorEscalationChannel channel;

    @BeforeEach
    void subir() throws Exception {
        linktor = new LinktorFalso();
        channel = new LinktorEscalationChannel(linktor.cliente());
    }

    @AfterEach
    void descer() {
        linktor.close();
    }

    @Test
    @DisplayName("com operador, atribui por user_id")
    void atribuiPorUserId() throws Exception {
        linktor.responde("POST", "/conversations/conv-1/assign", 200, "{\"success\":true,\"data\":{}}");

        channel.escalate(new EscalationRequest("t1", "conv-1", "u-1", "baixa confiança", Map.of()));

        assertThat(linktor.recebidas()).singleElement().satisfies(r ->
                assertThat(json.readTree(r.corpo())).isEqualTo(json.readTree("{\"user_id\":\"u-1\"}")));
    }

    @Test
    @DisplayName("sem operador falha dizendo por quê, sem chamar o Linktor")
    void semOperadorFalha() {
        assertThatThrownBy(() -> channel.escalate(EscalationRequest.of("t1", "conv-1", "baixa confiança")))
                .isInstanceOf(EscalationException.class)
                .hasMessageContaining("targetUserId")
                .hasMessageContaining("return-to-queue");
        assertThat(linktor.recebidas()).isEmpty();
    }
}
