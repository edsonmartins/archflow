package br.com.archflow.decision;

import java.util.List;
import java.util.Map;

/**
 * Uma pergunta tipada sobre um estado. Três formas, e só três: escolher entre opções, graduar numa
 * escala ordenada, ou dizer se uma condição vale. Cada uma tem a resposta que lhe cabe
 * ({@link Answer}).
 *
 * <p>É o vocabulário do contrato, e não de um fornecedor: um modelo de decisão, um LLM usado como
 * classificador e um conjunto de regras respondem às mesmas perguntas.</p>
 */
public sealed interface Question {

    String id();

    /** O que se quer saber, em linguagem natural. Pode ser vazio quando as opções já dizem tudo. */
    String instructions();

    /** Escolher uma opção. {@code options}: nome da opção → o que ela significa (pode ser vazio). */
    record Choice(String id, String instructions, Map<String, String> options) implements Question {
        public Choice {
            requireId(id);
            if (options == null || options.isEmpty()) {
                throw new IllegalArgumentException("choice '" + id + "' exige ao menos uma opção");
            }
            options = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(options));
            instructions = instructions == null ? "" : instructions;
        }
    }

    /** Graduar numa escala ordenada; o índice do nível é o valor. Exige ao menos dois níveis. */
    record Score(String id, String instructions, List<String> levels) implements Question {
        public Score {
            requireId(id);
            if (levels == null || levels.size() < 2) {
                throw new IllegalArgumentException("score '" + id + "' exige ao menos dois níveis");
            }
            levels = List.copyOf(levels);
            instructions = instructions == null ? "" : instructions;
        }
    }

    /** A condição vale? As descrições são opcionais e só ajudam o modelo a entender cada lado. */
    record YesNo(String id, String instructions, String whenTrue, String whenFalse)
            implements Question {
        public YesNo {
            requireId(id);
            instructions = instructions == null ? "" : instructions;
        }
    }

    private static void requireId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a pergunta exige um id");
        }
    }
}
