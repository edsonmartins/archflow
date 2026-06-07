package br.com.archflow.api.assist.dto;

/**
 * Corpo de erro retornado pelos endpoints de assist quando a IA está
 * indisponível (falha do modelo ou timeout) — HTTP 503.
 *
 * @param erro     código de erro estável (ex.: "IA_INDISPONIVEL")
 * @param mensagem detalhe legível
 */
public record AssistErrorResponse(
        String erro,
        String mensagem
) {
    public static AssistErrorResponse iaIndisponivel(String mensagem) {
        return new AssistErrorResponse("IA_INDISPONIVEL", mensagem);
    }
}
