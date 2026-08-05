package br.com.archflow.api.agent.vendax;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Quem agrupa os resultados é quem sabe agrupar.
 *
 * <p>Este runtime enxerga um invoke isolado. O Core enxerga a conversa — e sabe que cinco mensagens
 * chegadas no mesmo minuto, uma por produto, são o MESMO pedido. Derivar a chave da mensagem
 * transformaria cada item numa cotação separada; a primeira delas, incompleta por construção, é a
 * que o cliente receberia.</p>
 */
@DisplayName("A chave de idempotência do Core tem precedência")
class ChaveDoCoreTest {

    private VendaxInvoke invoke(String chaveDoCore) {
        return new VendaxInvoke("1.0", "t-1", "conv-1", "QP", "msg-9",
                "3 kg de carne seca", "STRONG", "pedido", "trace-1", null,
                "64336", "v-1", chaveDoCore, null);
    }

    @Test
    @DisplayName("com a chave do Core, ela é usada como está")
    void chaveDoCoreVence() {
        VendaxResult r = VendaxResult.ok(invoke("QP:conv-1:7"), "quote", "{}");

        assertThat(r.idempotencyKey())
                .as("é a janela do pedido, não a mensagem")
                .isEqualTo("QP:conv-1:7");
    }

    @Test
    @DisplayName("sem a chave, deriva da mensagem — o comportamento de antes")
    void semChaveDerivaDaMensagem() {
        VendaxResult r = VendaxResult.ok(invoke(null), "quote", "{}");

        assertThat(r.idempotencyKey()).isEqualTo("QP:msg-9");
    }

    /** Chave em branco é ausência disfarçada — tratar como preenchida agruparia tudo num balde só. */
    @Test
    @DisplayName("chave em branco conta como ausente")
    void brancoContaComoAusente() {
        assertThat(VendaxResult.ok(invoke("   "), "quote", "{}").idempotencyKey())
                .isEqualTo("QP:msg-9");
    }

    /** O erro precisa da mesma chave do sucesso: senão a falha não substitui o que estava lá. */
    @Test
    @DisplayName("o resultado de erro carrega a mesma chave")
    void erroUsaAMesmaChave() {
        assertThat(VendaxResult.error(invoke("QP:conv-1:7"), "falhou").idempotencyKey())
                .isEqualTo("QP:conv-1:7");
    }
}
