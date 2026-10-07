package br.com.archflow.decision;

import java.util.List;

/**
 * O que se pergunta e sobre o quê.
 *
 * @param state     o conteúdo a julgar: texto, objeto ou lista — o que o provedor aceitar
 * @param questions as perguntas independentes sobre o mesmo estado; um provedor pode respondê-las
 *                  numa só chamada, e nenhuma enxerga a resposta das outras
 */
public record DecisionRequest(Object state, List<Question> questions) {
    public DecisionRequest {
        if (questions == null || questions.isEmpty()) {
            throw new IllegalArgumentException("a decisão exige ao menos uma pergunta");
        }
        questions = List.copyOf(questions);
    }
}
