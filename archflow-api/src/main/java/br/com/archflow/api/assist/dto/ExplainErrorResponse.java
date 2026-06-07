package br.com.archflow.api.assist.dto;

/**
 * Diagnóstico em linguagem natural produzido pela IA para um erro de execução.
 *
 * <p>Contrato fixo, compartilhado com o backend Mentors (change #21).
 *
 * @param diagnostico       explicação do que aconteceu (obrigatório)
 * @param causaProvavel     causa provável; pode ser {@code null}
 * @param sugestaoCorrecao  sugestão acionável de correção (obrigatório; pode ser "")
 * @param confianca         nível de confiança: "ALTA" | "MEDIA" | "BAIXA"; pode ser {@code null}
 * @param modelo            id/nome do modelo LLM que produziu a resposta
 */
public record ExplainErrorResponse(
        String diagnostico,
        String causaProvavel,
        String sugestaoCorrecao,
        String confianca,
        String modelo
) {
}
