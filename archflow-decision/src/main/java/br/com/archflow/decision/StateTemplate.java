package br.com.archflow.decision;

import br.com.archflow.model.engine.ExecutionContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Monta o estado a julgar a partir de um modelo com {@code ${caminho}}.
 *
 * <p>Um valor que é <b>só</b> {@code ${caminho}} mantém o tipo do que foi resolvido (um objeto
 * continua objeto); misturado a texto vira texto. Um caminho que não resolve vira vazio — e não
 * erro: o estado é o que se tem, e a decisão julga o que há.</p>
 */
public final class StateTemplate {

    private static final Pattern VAR = Pattern.compile("\\$\\{([^}]+)}");

    private StateTemplate() {
    }

    /**
     * Resolve um caminho no contexto do fluxo: a chave inteira primeiro ({@code a.b} guardado assim),
     * depois a descida pelos mapas aninhados. {@code input}, se informado, vale para o caminho
     * {@code input}. É a mesma resolução das condições das arestas, para um {@code ${x.y}} querer
     * dizer a mesma coisa em todo lugar.
     */
    @SuppressWarnings("unchecked")
    public static Function<String, Object> fromContext(ExecutionContext context, Object input) {
        return path -> {
            if (input != null && path.equals("input")) {
                return input;
            }
            if (context == null) {
                return null;
            }
            java.util.Optional<Object> direto = context.get(path);
            if (direto.isPresent()) {
                return direto.get();
            }
            String[] partes = path.split("\\.");
            Object atual = context.get(partes[0]).orElse(null);
            for (int i = 1; i < partes.length && atual != null; i++) {
                atual = atual instanceof Map<?, ?> m ? ((Map<String, Object>) m).get(partes[i]) : null;
            }
            return atual;
        };
    }

    public static Object resolve(Object template, Function<String, Object> lookup) {
        if (template instanceof String s) {
            Matcher inteiro = VAR.matcher(s.trim());
            if (inteiro.matches()) {
                return lookup.apply(inteiro.group(1).trim());
            }
            Matcher m = VAR.matcher(s);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                Object valor = lookup.apply(m.group(1).trim());
                m.appendReplacement(sb, Matcher.quoteReplacement(valor == null ? "" : valor.toString()));
            }
            m.appendTail(sb);
            return sb.toString();
        }
        if (template instanceof Map<?, ?> mapa) {
            Map<String, Object> saida = new LinkedHashMap<>();
            mapa.forEach((k, v) -> saida.put(String.valueOf(k), resolve(v, lookup)));
            return saida;
        }
        if (template instanceof List<?> lista) {
            List<Object> saida = new ArrayList<>();
            lista.forEach(v -> saida.add(resolve(v, lookup)));
            return saida;
        }
        return template;
    }
}
