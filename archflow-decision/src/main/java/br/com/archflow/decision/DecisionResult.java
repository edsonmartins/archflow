package br.com.archflow.decision;

import java.util.Map;

/**
 * O que o provedor respondeu.
 *
 * @param answers    resposta por id de pergunta
 * @param model      o modelo que de fato respondeu (com a versão, quando o provedor a informa)
 * @param provider   quem serviu a chamada
 * @param calibrated as probabilidades vêm do próprio modelo (verdadeiro) ou foram estimadas/declaradas
 *                   (falso: um LLM que "diz" estar 95% seguro não é calibrado). Quem decide por limiar
 *                   deve tratar as duas coisas de forma diferente.
 * @param usage      consumo; nulo quando o provedor não informa
 */
public record DecisionResult(Map<String, Answer> answers, String model, String provider,
                             boolean calibrated, Usage usage) {

    public DecisionResult {
        answers = answers == null ? Map.of() : Map.copyOf(answers);
    }

    /** {@code costUsd} nulo é "não informado", nunca "de graça". */
    public record Usage(Long inputTokens, Long outputTokens, Double costUsd) {
    }
}
