package br.com.archflow.plugin.api.spi;

import br.com.archflow.model.ai.domain.Goal;
import br.com.archflow.model.ai.domain.Task;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapta a entrada que chega a {@code AIComponent.execute} ao tipo que a operação espera.
 *
 * <p>Por que existe: quem chama um componente do catálogo raramente tem o tipo exato que o plugin
 * declarou. O Executar do designer manda {@code {"message": "..."}}, o {@code RunInputSeeding} põe esse
 * mapa inteiro em {@code input}, a fila de invocações entrega o {@code payload} (também um mapa) e um
 * checkpoint devolve o {@code Analysis} do passo anterior como mapa. Os plugins faziam
 * {@code (String) input} / {@code (Task) input} e quebravam com {@code ClassCastException} — ou, pior,
 * transformavam {@code "{message=...}"} como se fosse o texto do usuário.</p>
 *
 * <p>A regra do texto é a mesma do {@code mcp-agent} ({@code McpAgentComponent.mensagemDe}): {@code input},
 * depois {@code message}; sem nenhuma delas, o mapa vira JSON — um texto que ainda diz o que veio, em vez do
 * {@code toString} de Java.</p>
 */
public final class ComponentInputs {

    /** Chaves que carregam "o texto do usuário", em ordem de preferência. */
    public static final List<String> TEXT_KEYS =
            List.of("input", "message");

    private ComponentInputs() {
    }

    /**
     * O texto da entrada: a própria string, o primeiro campo de texto de um mapa ou o JSON do mapa.
     * {@code null} continua {@code null} — cada plugin já sabe responder a "sem entrada".
     */
    public static String text(Object input) {
        if (input == null) {
            return null;
        }
        if (input instanceof CharSequence s) {
            return s.toString();
        }
        if (input instanceof Map<?, ?> map) {
            String fromKeys = textField(map);
            return fromKeys != null ? fromKeys : json(map);
        }
        if (input instanceof Collection<?>) {
            return json(input);
        }
        return String.valueOf(input);
    }

    /**
     * O texto de um dos {@link #TEXT_KEYS} do mapa, ou {@code null} quando não há nenhum. Diferente de
     * {@link #text}, não cai no JSON: serve para quem precisa saber se o usuário mandou texto.
     */
    public static String textField(Map<?, ?> map) {
        if (map == null) {
            return null;
        }
        for (String key : TEXT_KEYS) {
            Object value = map.get(key);
            if (value == null) {
                continue;
            }
            // {"input": {"message": "..."}} — o input do fluxo pode ser ele mesmo o mapa da entrada.
            String s = value instanceof Map<?, ?> nested ? textField(nested) : text(value);
            if (s != null && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    /**
     * Uma {@link Task} a partir da entrada.
     *
     * <ul>
     *   <li>{@code Task} passa como está;</li>
     *   <li>mapa: {@code type} (ou {@code defaultType}) e, como parâmetros, {@code parameters} quando é um
     *       mapa, senão o próprio mapa;</li>
     *   <li>string: tarefa de {@code defaultType} com o texto.</li>
     * </ul>
     *
     * <p>O texto encontrado entra no parâmetro {@code textKey} (sem sobrescrever) — o nome que o próprio
     * agente lê ({@code request} no conversacional, {@code query} no de análise). {@code null} quando o agente
     * não lê texto dos parâmetros.</p>
     */
    public static Task task(Object input, String defaultType, String textKey) {
        if (input == null) {
            return null;
        }
        if (input instanceof Task task) {
            return task;
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        String type = defaultType;
        String text;
        if (input instanceof Map<?, ?> map) {
            Object declared = map.get("type");
            if (declared != null && !declared.toString().isBlank()) {
                type = declared.toString();
            }
            Object nested = map.get("parameters");
            Map<?, ?> source = nested instanceof Map<?, ?> p ? p : map;
            source.forEach((k, v) -> parameters.put(String.valueOf(k), v));
            text = textField(source);
            if (text == null && source != map) {
                text = textField(map);
            }
        } else {
            text = text(input);
        }
        if (text != null && textKey != null) {
            parameters.putIfAbsent(textKey, text);
        }
        return Task.of(type, parameters);
    }

    /**
     * Um {@link Goal} a partir da entrada: o próprio, um mapa com {@code description} (ou texto) e
     * {@code successCriteria}, ou uma string como descrição.
     */
    public static Goal goal(Object input) {
        if (input == null) {
            return null;
        }
        if (input instanceof Goal goal) {
            return goal;
        }
        if (input instanceof Map<?, ?> map) {
            Object description = map.get("description");
            String text = description != null ? description.toString() : text(map);
            List<String> criteria = map.get("successCriteria") instanceof Collection<?> c
                    ? c.stream().map(String::valueOf).toList()
                    : List.of();
            return Goal.of(text, criteria);
        }
        return Goal.of(text(input), List.of());
    }

    /** JSON mínimo (mapas, listas, strings, números, booleanos); o resto entra como string. */
    static String json(Object value) {
        StringBuilder out = new StringBuilder();
        writeJson(value, out);
        return out.toString();
    }

    private static void writeJson(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(String.valueOf(e.getKey()), out);
                out.append(':');
                writeJson(e.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof Collection<?> list) {
            out.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeJson(item, out);
            }
            out.append(']');
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else {
            writeString(value.toString(), out);
        }
    }

    private static void writeString(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
