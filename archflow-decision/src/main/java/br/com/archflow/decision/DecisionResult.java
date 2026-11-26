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

    /**
     * {@code costUsd} nulo é "não informado", nunca "de graça".
     *
     * @param billedProvider quem cobra a chamada, quando não é o {@link DecisionResult#provider()}: o
     *                       provider {@code llm} serve a decisão, mas quem cobra é o LLM que a
     *                       plataforma resolveu ({@code openrouter}). É o par cobrado que casa com a
     *                       tabela de preços ({@code openrouter/<modelo>}) — com {@code llm} no lugar,
     *                       a decisão nunca tinha preço (RCHFLW-170). Nulo: o próprio provider.
     * @param billedModel    o modelo cobrado, nesse mesmo sentido; nulo: o {@link DecisionResult#model()}
     */
    public record Usage(Long inputTokens, Long outputTokens, Double costUsd,
                        String billedProvider, String billedModel) {

        /** O consumo de quem serviu e cobrou a chamada — todo provider, menos o {@code llm}. */
        public Usage(Long inputTokens, Long outputTokens, Double costUsd) {
            this(inputTokens, outputTokens, costUsd, null, null);
        }
    }
}
