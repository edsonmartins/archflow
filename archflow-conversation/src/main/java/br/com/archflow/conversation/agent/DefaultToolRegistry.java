package br.com.archflow.conversation.agent;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementação em memória, thread-safe, de {@link ToolRegistry}. Nomes são
 * normalizados para minúsculas para lookup case-insensitive.
 *
 * @since 1.0.0
 */
public class DefaultToolRegistry implements ToolRegistry {

    private final Map<String, ConversationTool> tools = new ConcurrentHashMap<>();
    private final Map<String, String> descriptions = new ConcurrentHashMap<>();

    @Override
    public void register(String name, String description, ConversationTool tool) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name is required");
        }
        if (tool == null) {
            throw new IllegalArgumentException("tool is required");
        }
        String key = name.trim().toLowerCase(Locale.ROOT);
        tools.put(key, tool);
        descriptions.put(key, description != null ? description : "");
    }

    @Override
    public Optional<ConversationTool> get(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(tools.get(name.trim().toLowerCase(Locale.ROOT)));
    }

    @Override
    public Set<String> names() {
        return Set.copyOf(tools.keySet());
    }

    @Override
    public Map<String, String> descriptions() {
        return Map.copyOf(descriptions);
    }

    @Override
    public String describe() {
        // ordem estável por nome
        Map<String, String> sorted = new LinkedHashMap<>();
        descriptions.keySet().stream().sorted().forEach(k -> sorted.put(k, descriptions.get(k)));
        StringBuilder sb = new StringBuilder();
        sorted.forEach((name, desc) ->
                sb.append("- ").append(name).append(": ").append(desc).append('\n'));
        return sb.toString().stripTrailing();
    }
}
