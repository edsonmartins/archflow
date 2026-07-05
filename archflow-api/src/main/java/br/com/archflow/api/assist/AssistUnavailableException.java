package br.com.archflow.api.assist;

/**
 * Lançada quando o modelo de IA está indisponível (falha na chamada ou timeout).
 * Mapeada para HTTP 503 com corpo {@code {"erro":"IA_INDISPONIVEL", ...}}.
 */
public class AssistUnavailableException extends RuntimeException {
    public AssistUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
