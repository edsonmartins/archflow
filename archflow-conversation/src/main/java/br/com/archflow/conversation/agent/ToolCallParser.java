package br.com.archflow.conversation.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extrai uma chamada de ferramenta do texto do LLM usando o protocolo de
 * marcadores (mesmo padrão do WhatsAppAgentService do gestor-rq):
 *
 * <pre>
 *   [TOOL: nome]
 *   [PARAMS: chave=valor, outra=valor]
 * </pre>
 *
 * <p>Dependency-free (sem JSON). Para params em JSON, um produto pode fornecer
 * um parser próprio — esta classe cobre o formato {@code k=v} separado por vírgula.
 *
 * @since 1.0.0
 */
public final class ToolCallParser {

    private static final Pattern TOOL =
            Pattern.compile("\\[TOOL:\\s*([\\w.\\-/]+)\\s*]", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARAMS =
            Pattern.compile("\\[PARAMS:\\s*(.*?)\\s*]", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private ToolCallParser() {
    }

    /** Chamada de ferramenta detectada. */
    public record ToolCall(String tool, Map<String, Object> params) {}

    /**
     * Detecta a primeira chamada de ferramenta no texto.
     *
     * @return a chamada, ou {@link Optional#empty()} se não houver marcador {@code [TOOL: ...]}
     */
    public static Optional<ToolCall> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher toolMatcher = TOOL.matcher(text);
        if (!toolMatcher.find()) {
            return Optional.empty();
        }
        String name = toolMatcher.group(1);
        Map<String, Object> params = new LinkedHashMap<>();
        Matcher paramsMatcher = PARAMS.matcher(text);
        if (paramsMatcher.find()) {
            parseKeyValues(paramsMatcher.group(1), params);
        }
        return Optional.of(new ToolCall(name, params));
    }

    private static void parseKeyValues(String raw, Map<String, Object> out) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (String pair : raw.split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if (!key.isEmpty()) {
                out.put(key, value);
            }
        }
    }
}
