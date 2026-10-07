package br.com.archflow.decision;

import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.engine.ExecutionKeys;

import java.util.Optional;

/**
 * Quem recebe o consumo de cada decisão. O hospedeiro (o contador de uso da execução) se registra
 * no contexto, e o componente reporta cada chamada que de fato foi feita — inclusive as de uma
 * cascata ou de um consenso —, para um teto de custo enxergar decisões como enxerga o resto.
 *
 * <p>{@code costUsd} é o custo <b>declarado pelo provedor</b>; nulo é "não informado", nunca zero.</p>
 */
public interface DecisionUsageListener {

    /** Onde o ouvinte viaja no contexto; transitório, nunca vai para o estado durável. */
    String CONTEXT_KEY = ExecutionKeys.TRANSIENT_PREFIX + "decisionUsage";

    void onDecision(String provider, String model, Long inputTokens, Long outputTokens, Double costUsd);

    static Optional<DecisionUsageListener> from(ExecutionContext context) {
        if (context == null) {
            return Optional.empty();
        }
        return context.get(CONTEXT_KEY)
                .filter(DecisionUsageListener.class::isInstance)
                .map(DecisionUsageListener.class::cast);
    }
}
