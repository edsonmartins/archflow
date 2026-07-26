package br.com.archflow.sdk;

/**
 * O servidor archflow respondeu com erro, ou não respondeu.
 *
 * @since 1.1.0
 */
public class ArchflowClientException extends RuntimeException {

    private final int statusCode;
    private final String responseBody;

    ArchflowClientException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
        this.responseBody = null;
    }

    ArchflowClientException(String message, int statusCode, String responseBody) {
        super(message + " (HTTP " + statusCode + ")"
                + (responseBody == null || responseBody.isBlank() ? "" : ": " + responseBody));
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    /** Código HTTP, ou {@code -1} quando a requisição nem chegou a ter resposta. */
    public int getStatusCode() {
        return statusCode;
    }

    /** Corpo da resposta de erro, se houve. */
    public String getResponseBody() {
        return responseBody;
    }
}
