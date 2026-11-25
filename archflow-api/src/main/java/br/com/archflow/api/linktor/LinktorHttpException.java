package br.com.archflow.api.linktor;

import java.io.IOException;

/**
 * O Linktor respondeu, mas com um status fora de 2xx.
 *
 * <p>Continua sendo uma {@link IOException}, então quem já tratava falha de rede e falha HTTP do mesmo
 * jeito não muda. Existe para quem precisa distinguir uma recusa: {@code GET /users} exige papel
 * {@code admin}/{@code owner} no Linktor, e uma chave de API (papel {@code api}) recebe 403. A tela de
 * transferência diz isso ao operador em vez de mostrar "falha de rede".</p>
 */
public class LinktorHttpException extends IOException {

    private final int status;

    public LinktorHttpException(int status, String message) {
        super(message);
        this.status = status;
    }

    /** Status HTTP que o Linktor devolveu. */
    public int status() {
        return status;
    }
}
