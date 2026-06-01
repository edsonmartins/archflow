package br.com.archflow.conversation.agent;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Registro de {@link ConversationTool}s disponíveis para um agente conversacional,
 * indexadas por nome, com descrições para compor o system prompt.
 *
 * @since 1.0.0
 */
public interface ToolRegistry {

    /** Registra (ou substitui) uma ferramenta. */
    void register(String name, String description, ConversationTool tool);

    /** Ferramenta por nome (case-insensitive). */
    Optional<ConversationTool> get(String name);

    /** Nomes registrados. */
    Set<String> names();

    /** Mapa nome → descrição. */
    Map<String, String> descriptions();

    /** Bloco legível "- nome: descrição" para injetar no system prompt. */
    String describe();
}
