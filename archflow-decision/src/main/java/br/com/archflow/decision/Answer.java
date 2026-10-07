package br.com.archflow.decision;

import java.util.Map;

/**
 * A resposta a uma {@link Question}. Toda resposta sabe dizer o quanto o modelo está seguro dela
 * ({@link #confidence()}, de 0 a 1) — é o que permite decidir entre agir, pedir revisão e escalar.
 */
public sealed interface Answer {

    /** 0 = máxima incerteza, 1 = certeza máxima. */
    double confidence();

    /** O valor da resposta em forma neutra: a opção, o nível ou a probabilidade. */
    Object value();

    /** Opção escolhida, com a distribuição entre as opções. */
    record Choice(String choice, Map<String, Double> probabilities, double confidence)
            implements Answer {
        public Choice {
            probabilities = probabilities == null ? Map.of() : Map.copyOf(probabilities);
        }

        @Override
        public Object value() {
            return choice;
        }
    }

    /** Posição na escala; {@code score} é fracionário (a média ponderada dos níveis). */
    record Score(double score, Map<String, Double> probabilities, Map<String, String> legend,
                 double confidence) implements Answer {
        public Score {
            probabilities = probabilities == null ? Map.of() : Map.copyOf(probabilities);
            legend = legend == null ? Map.of() : Map.copyOf(legend);
        }

        @Override
        public Object value() {
            return score;
        }
    }

    /**
     * Probabilidade de a condição valer. Não há confiança própria no fio: ela é derivada, com a mesma
     * fórmula da escolha entre duas opções ({@code |2p − 1|}) — 0,5 é incerteza total, 0 ou 1 é certeza.
     */
    record YesNo(double probability) implements Answer {
        @Override
        public double confidence() {
            return Math.abs(2 * probability - 1);
        }

        @Override
        public Object value() {
            return probability;
        }
    }
}
