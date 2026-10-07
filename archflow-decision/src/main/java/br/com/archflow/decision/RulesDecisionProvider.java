package br.com.archflow.decision;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Decide por regras determinísticas — o primeiro degrau de uma cascata: o que um padrão resolve não
 * precisa de modelo, e um modelo só entra no que as regras não resolvem.
 *
 * <p>As regras vêm das opções do nó, por pergunta ({@code options.rules.<id>}). Um padrão é um trecho
 * de texto (comparado sem diferenciar maiúsculas) ou, com o prefixo {@code re:}, uma expressão regular.
 * O texto julgado é o {@code state}, se for texto, ou o seu JSON.</p>
 *
 * <pre>
 * rules:
 *   time:                                  # choice
 *     match: { pagamento: ["cobrança", "re:boleto|pix"], conta: ["senha", "login"] }
 *     otherwise: conta                     # sem isto, nada casar é erro
 *   e_bug:                                 # yes/no
 *     matchAny: ["erro", "não funciona"]
 *   urgencia:                              # score: índice do nível → padrões
 *     match: { "2": ["fora do ar"], "1": ["lento"] }
 *     otherwise: 0
 * </pre>
 *
 * <h2>Confiança</h2>
 * <p>Uma regra que casou sozinha é certeza (1). Duas opções casando é ambiguidade: a probabilidade se
 * reparte e a confiança cai. Cair no {@code otherwise} é um palpite, não uma decisão: confiança 0 — a
 * rota escala, a menos que o fluxo trate esse caso.</p>
 */
public final class RulesDecisionProvider implements DecisionProvider {

    public static final String ID = "rules";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String id() {
        return ID;
    }

    @Override
    @SuppressWarnings("unchecked")
    public DecisionResult decide(DecisionRequest request, DecisionCall call) throws DecisionException {
        Map<String, Object> regras = call.options().get("rules") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        String texto = texto(request.state()).toLowerCase();
        Map<String, Answer> answers = new LinkedHashMap<>();
        for (Question q : request.questions()) {
            if (!(regras.get(q.id()) instanceof Map<?, ?> regra)) {
                throw new DecisionException("sem regras para a pergunta '" + q.id() + "'", false);
            }
            answers.put(q.id(), switch (q) {
                case Question.Choice c -> choice(c, (Map<String, Object>) regra, texto);
                case Question.Score s -> score(s, (Map<String, Object>) regra, texto);
                case Question.YesNo y -> yesNo((Map<String, Object>) regra, texto);
            });
        }
        return new DecisionResult(answers, "rules", ID, true, null);
    }

    @SuppressWarnings("unchecked")
    private static Answer choice(Question.Choice q, Map<String, Object> regra, String texto)
            throws DecisionException {
        List<String> casadas = new ArrayList<>();
        Map<String, Object> match = regra.get("match") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        for (String opcao : q.options().keySet()) {
            if (casa(match.get(opcao), texto)) {
                casadas.add(opcao);
            }
        }
        Map<String, Double> probs = new LinkedHashMap<>();
        q.options().keySet().forEach(o -> probs.put(o, 0.0));
        if (casadas.isEmpty()) {
            Object otherwise = regra.get("otherwise");
            if (!(otherwise instanceof String s) || !q.options().containsKey(s)) {
                throw new DecisionException("nenhuma regra casou em '" + q.id()
                        + "' e não há 'otherwise' válido", false);
            }
            // Um palpite: distribuição uniforme, confiança 0.
            q.options().keySet().forEach(o -> probs.put(o, 1.0 / q.options().size()));
            return new Answer.Choice(s, probs, 0.0);
        }
        casadas.forEach(o -> probs.put(o, 1.0 / casadas.size()));
        int n = q.options().size();
        double pmax = 1.0 / casadas.size();
        double confianca = n == 1 ? 1.0 : (pmax - 1.0 / n) / (1.0 - 1.0 / n);
        return new Answer.Choice(casadas.get(0), probs, confianca);
    }

    @SuppressWarnings("unchecked")
    private static Answer score(Question.Score q, Map<String, Object> regra, String texto)
            throws DecisionException {
        Map<String, Object> match = regra.get("match") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        List<Integer> casados = new ArrayList<>();
        for (int i = 0; i < q.levels().size(); i++) {
            if (casa(match.get(String.valueOf(i)), texto)) {
                casados.add(i);
            }
        }
        Map<String, Double> probs = new LinkedHashMap<>();
        Map<String, String> legenda = new LinkedHashMap<>();
        for (int i = 0; i < q.levels().size(); i++) {
            probs.put(String.valueOf(i), 0.0);
            legenda.put(String.valueOf(i), q.levels().get(i));
        }
        if (casados.isEmpty()) {
            if (!(regra.get("otherwise") instanceof Number n)
                    || n.intValue() < 0 || n.intValue() >= q.levels().size()) {
                throw new DecisionException("nenhuma regra casou em '" + q.id()
                        + "' e não há 'otherwise' válido", false);
            }
            probs.replaceAll((k, v) -> 1.0 / q.levels().size());
            return new Answer.Score(n.intValue(), probs, legenda, 0.0);
        }
        casados.forEach(i -> probs.put(String.valueOf(i), 1.0 / casados.size()));
        double media = casados.stream().mapToInt(Integer::intValue).average().orElse(0);
        // Vários níveis casando: o nível é a média e a confiança cai com o espalhamento.
        double confianca = casados.size() == 1 ? 1.0
                : Math.max(0.0, 1.0 - (casados.get(casados.size() - 1) - casados.get(0))
                        / (double) (q.levels().size() - 1));
        return new Answer.Score(media, probs, legenda, confianca);
    }

    private static Answer yesNo(Map<String, Object> regra, String texto) {
        // Casar é certeza de sim; não casar é certeza de não — regras não têm meio-termo.
        return new Answer.YesNo(casa(regra.get("matchAny"), texto) ? 1.0 : 0.0);
    }

    private static boolean casa(Object padroes, String texto) {
        if (!(padroes instanceof List<?> lista)) {
            return false;
        }
        for (Object p : lista) {
            if (p == null) {
                continue;
            }
            String padrao = p.toString();
            if (padrao.startsWith("re:")) {
                if (Pattern.compile(padrao.substring(3), Pattern.CASE_INSENSITIVE).matcher(texto).find()) {
                    return true;
                }
            } else if (!padrao.isBlank() && texto.contains(padrao.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static String texto(Object state) {
        if (state == null) {
            return "";
        }
        if (state instanceof CharSequence s) {
            return s.toString();
        }
        try {
            return JSON.writeValueAsString(state);
        } catch (Exception e) {
            return String.valueOf(state);
        }
    }
}
