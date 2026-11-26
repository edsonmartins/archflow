package br.com.archflow.api.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RCHFLW-134: a saída gravada do passo é cortada em {@link StepRecordingListener#MAX_OUTPUT_CHARS} {@code char}s, e
 * um emoji fora do BMP ocupa dois. Com o corte caindo entre eles, sobrava a metade alta sozinha — o detalhe da
 * execução mostrava "🎉?…(truncado)" (exec-66a51c1e). Os três caminhos de corte (texto, JSON pelo writer limitado e
 * o toString de último recurso) terminam num caractere inteiro.
 */
@DisplayName("saída truncada não parte um par surrogate (RCHFLW-134)")
class SaidaTruncadaSemPartirParTest {

    private static final int LIMITE = StepRecordingListener.MAX_OUTPUT_CHARS;
    private static final String EMOJI = "🎉";

    @Test
    @DisplayName("texto: o emoji cortado ao meio sai inteiro do recorte, sem metade sobrando")
    void textoNaoPartePar() {
        // A metade alta cai exatamente no último char que caberia.
        String texto = "x".repeat(LIMITE - 1) + EMOJI + "depois";

        String gravado = StepRecordingListener.serialize(texto);

        assertThat(gravado).isEqualTo("x".repeat(LIMITE - 1) + StepRecordingListener.TRUNCATED_SUFFIX);
        assertThat(semMetadeSolta(gravado)).isTrue();
    }

    @Test
    @DisplayName("texto: emoji inteiro antes do limite continua no recorte")
    void emojiInteiroFica() {
        String texto = "x".repeat(LIMITE - 2) + EMOJI + "depois";

        String gravado = StepRecordingListener.serialize(texto);

        assertThat(gravado).isEqualTo("x".repeat(LIMITE - 2) + EMOJI + StepRecordingListener.TRUNCATED_SUFFIX);
    }

    @Test
    @DisplayName("JSON (writer limitado): o corte no meio do emoji também não deixa metade")
    void jsonNaoPartePar() {
        // {"texto":" tem 10 chars; a metade alta do emoji fica no último char do limite.
        String valor = "x".repeat(LIMITE - 1 - "{\"texto\":\"".length()) + EMOJI + "depois";

        String gravado = StepRecordingListener.serialize(Map.of("texto", valor));

        assertThat(gravado).endsWith("x" + StepRecordingListener.TRUNCATED_SUFFIX);
        assertThat(gravado).hasSize(LIMITE - 1 + StepRecordingListener.TRUNCATED_SUFFIX.length());
        assertThat(semMetadeSolta(gravado)).isTrue();
    }

    @Test
    @DisplayName("writer limitado: a metade alta que fechou um write exato some quando o próximo estoura")
    void metadeAltaNoFimDeUmWriteAnterior() throws Exception {
        var writer = new StepRecordingListener.BoundedWriter(4);
        writer.write("abc" + EMOJI.charAt(0));
        try {
            writer.write(String.valueOf(EMOJI.charAt(1)));
        } catch (java.io.IOException esperado) {
            // estouro: é assim que o writer interrompe o Jackson
        }

        assertThat(writer.overflowed()).isTrue();
        assertThat(writer.text()).isEqualTo("abc");
    }

    @Test
    @DisplayName("toString de último recurso: o mesmo corte")
    void toStringNaoPartePar() {
        Object semJson = new Object() {
            public String getValue() {
                throw new IllegalStateException("sem getter");
            }

            @Override
            public String toString() {
                return "x".repeat(LIMITE - 1) + EMOJI + "depois";
            }
        };

        String gravado = StepRecordingListener.serialize(semJson);

        assertThat(gravado).isEqualTo("x".repeat(LIMITE - 1) + StepRecordingListener.TRUNCATED_SUFFIX);
    }

    /** Nenhuma metade alta sem a baixa logo depois, nem baixa sem a alta logo antes. */
    private static boolean semMetadeSolta(String texto) {
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            if (Character.isHighSurrogate(c)
                    && (i + 1 >= texto.length() || !Character.isLowSurrogate(texto.charAt(i + 1)))) {
                return false;
            }
            if (Character.isLowSurrogate(c) && (i == 0 || !Character.isHighSurrogate(texto.charAt(i - 1)))) {
                return false;
            }
        }
        return true;
    }
}
