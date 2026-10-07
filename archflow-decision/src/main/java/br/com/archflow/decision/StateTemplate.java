package br.com.archflow.decision;

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
