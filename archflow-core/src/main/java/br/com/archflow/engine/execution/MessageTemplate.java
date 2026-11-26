package br.com.archflow.engine.execution;

import br.com.archflow.model.engine.ExecutionContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Um texto com referências {@code ${…}} — a "Mensagem do usuário" do LLM Chat (RCHFLW-120).
 *
 * <p><b>Por que existe.</b> O motor passa a saída de cada passo como entrada do seguinte. Num fluxo
 * Entrada → decision → condition → LLM Chat, o LLM recebia o mapa da classificação (route, answers…) como
 * mensagem do usuário e respondia "notei que você enviou um registro técnico/JSON". O nó não tinha como dizer
 * qual valor é a mensagem. Com o template, ele diz: {@code ${<id da Entrada>}} (a mensagem que chegou à
 * execução) ou {@code "Cliente: ${receber}"}.</p>
 *
 * <p><b>A mesma leitura das condições.</b> Cada {@code ${caminho}} é resolvido por
 * {@link ConditionEvaluator#resolvePath}: as mesmas raízes ({@code ${input}} é a entrada do passo — a saída do
 * passo anterior —, {@code ${<id>}} a saída de um passo, {@code ${<id>.campo}} um campo dela) e o mesmo
 * resolvedor. A checagem estática é a do {@link ControlExpressionRules#checkTemplate}, que lê o texto por
 * {@link #references}: o editor, o servidor e a execução não podem discordar sobre o que é uma referência.</p>
 *
 * <p><b>As regras.</b></p>
 * <ul>
 *   <li>Fora de {@code ${}} o texto vai como está; dá para misturar ({@code "Cliente: ${receber}"}).</li>
 *   <li>Texto vai como texto; número e booleano, pelo {@code toString}; mapa, lista e demais objetos viram
 *       <b>JSON</b> — o modelo vê os dados, não um {@code toString} de Java (o mesmo que a entrada-mapa já
 *       virava, {@code LangChainAdapterComponent.messageText}).</li>
 *   <li>Uma referência que não resolve ({@code null}) <b>falha</b> ({@link UnresolvedReferenceException}), com
 *       todas as que faltam: mandar o texto vazio, ou o literal {@code ${…}}, faria o modelo responder a outra
 *       coisa e o passo terminar com sucesso.</li>
 *   <li>Um <code>${</code> sem fechar, vazio ou aninhado ({@code ${a${b}}}) é {@link MalformedTemplateException}: o
 *       literal iria ao modelo.</li>
 * </ul>
 */
public final class MessageTemplate {

    private static final ObjectMapper JSON = new ObjectMapper();

    private MessageTemplate() {
    }

    /** O <code>${…}</code> que não é uma referência: sem fechar, vazio ou com outro {@code $}/chave dentro. */
    public static final class MalformedTemplateException extends IllegalArgumentException {
        private final String fragment;

        MalformedTemplateException(String fragment) {
            super("«" + fragment + "» não é uma referência: escreva ${caminho}, com a chave fechada e um caminho "
                    + "dentro");
            this.fragment = fragment;
        }

        /** O trecho do template, a partir do <code>${</code> que não fecha (ou até a chave que fecha). */
        public String fragment() {
            return fragment;
        }
    }

    /** As referências que não resolveram no contexto da execução. */
    public static final class UnresolvedReferenceException extends IllegalArgumentException {
        private final List<String> references;

        UnresolvedReferenceException(List<String> references) {
            super("referência sem valor nesta execução: "
                    + String.join(", ", references.stream().map(r -> "${" + r + "}").toList()));
            this.references = List.copyOf(references);
        }

        /** Os caminhos, sem {@code ${}}, na ordem em que aparecem no template. */
        public List<String> references() {
            return references;
        }
    }

    /**
     * Os caminhos das referências do template, na ordem e sem repetição.
     *
     * @throws MalformedTemplateException no primeiro <code>${</code> que não é uma referência
     */
    public static List<String> references(String template) {
        Set<String> paths = new LinkedHashSet<>();
        parse(template, null, paths);
        return List.copyOf(paths);
    }

    /**
     * O texto com cada {@code ${caminho}} trocado pelo valor no contexto.
     *
     * @throws MalformedTemplateException   quando o template tem um <code>${</code> que não é referência
     * @throws UnresolvedReferenceException quando alguma referência não tem valor
     */
    public static String render(String template, ExecutionContext context) {
        StringBuilder out = new StringBuilder();
        List<String> missing = new ArrayList<>();
        parse(template, new Sink(out, missing, context), new LinkedHashSet<>());
        if (!missing.isEmpty()) {
            throw new UnresolvedReferenceException(missing);
        }
        return out.toString();
    }

    /** Valor que não é texto vira JSON; número e booleano, o próprio texto. */
    static String asText(Object value) {
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean
                || value instanceof Character) {
            return String.valueOf(value);
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException | RuntimeException e) {
            return String.valueOf(value);
        }
    }

    /** Onde o render escreve; {@code null} no parse só de checagem. */
    private record Sink(StringBuilder out, List<String> missing, ExecutionContext context) {
    }

    private static void parse(String template, Sink sink, Set<String> paths) {
        if (template == null) {
            return;
        }
        int i = 0;
        while (i < template.length()) {
            int open = template.indexOf("${", i);
            if (open < 0) {
                append(sink, template.substring(i));
                return;
            }
            append(sink, template.substring(i, open));
            int close = template.indexOf('}', open + 2);
            if (close < 0) {
                throw new MalformedTemplateException(template.substring(open));
            }
            String path = template.substring(open + 2, close).trim();
            // "${a${b}}" fecha na primeira chave e leria a chave "a${b": não é a referência que se quis escrever.
            if (path.isEmpty() || path.contains("$") || path.contains("{")) {
                throw new MalformedTemplateException(template.substring(open, close + 1));
            }
            paths.add(path);
            if (sink != null) {
                Object value = ConditionEvaluator.resolvePath(path, sink.context());
                if (value == null) {
                    if (!sink.missing().contains(path)) {
                        sink.missing().add(path);
                    }
                } else {
                    sink.out().append(asText(value));
                }
            }
            i = close + 1;
        }
    }

    private static void append(Sink sink, String literal) {
        if (sink != null) {
            sink.out().append(literal);
        }
    }
}
