package br.com.archflow.conversation.persona;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * Persona resolver with 4-layer fallback strategy, mirroring the SAC agent's
 * {@code AgentPersonaResolver}.
 *
 * <p>Resolution order:
 * <ol>
 *   <li><b>LLM classifier</b> — calls a function (provided by product) to classify intent.
 *       Skipped if {@code llmClassifier} is null.</li>
 *   <li><b>Keyword regex</b> — matches user message against each persona's keyword patterns.</li>
 *   <li><b>Sticky context</b> — if a previous turn already resolved a persona for this conversation,
 *       reuse it (cached internally by conversationId).</li>
 *   <li><b>Default fallback</b> — returns the registered default persona, if any.</li>
 * </ol>
 *
 * <p>Thread-safe.
 */
public class PersonaResolver {

    private static final Logger log = LoggerFactory.getLogger(PersonaResolver.class);

    private final List<Persona> personas;
    private final Persona defaultPersona;
    private final Function<String, Optional<String>> llmClassifier;
    private final ConcurrentMap<String, String> stickyContext = new ConcurrentHashMap<>();

    /**
     * @param personas       All personas available for resolution
     * @param defaultPersona Persona returned when no match found (can be null)
     * @param llmClassifier  Optional function: message → personaId. Null disables LLM layer.
     */
    public PersonaResolver(List<Persona> personas, Persona defaultPersona,
                           Function<String, Optional<String>> llmClassifier) {
        this.personas = List.copyOf(personas);
        this.defaultPersona = defaultPersona;
        this.llmClassifier = llmClassifier;
    }

    /**
     * Convenience constructor without LLM classifier (only keyword + sticky + default).
     */
    public PersonaResolver(List<Persona> personas, Persona defaultPersona) {
        this(personas, defaultPersona, null);
    }

    /**
     * Resolve the persona for a message in the given conversation.
     *
     * @param conversationId Identifier of the conversation (for sticky cache)
     * @param message        User message to classify
     * @return The resolved persona (never null if a default is set)
     */
    public Optional<Persona> resolve(String conversationId, String message) {
        // Layer 1: LLM classifier
        if (llmClassifier != null) {
            Optional<String> llmResult = safeLlmClassify(message);
            if (llmResult.isPresent()) {
                Optional<Persona> p = findById(llmResult.get());
                if (p.isPresent()) {
                    log.debug("Persona resolved via LLM: {}", p.get().id());
                    stickyContext.put(conversationId, p.get().id());
                    return p;
                }
            }
        }

        // Layer 2: Keyword regex
        Optional<Persona> keyword = personas.stream()
                .filter(p -> p.matchesKeywords(message))
                .findFirst();
        if (keyword.isPresent()) {
            log.debug("Persona resolved via keyword: {}", keyword.get().id());
            stickyContext.put(conversationId, keyword.get().id());
            return keyword;
        }

        // Layer 3: Sticky context
        String stickyId = stickyContext.get(conversationId);
        if (stickyId != null) {
            Optional<Persona> sticky = findById(stickyId);
            if (sticky.isPresent()) {
                log.debug("Persona resolved via sticky context: {}", sticky.get().id());
                return sticky;
            }
        }

        // Layer 4: Default
        if (defaultPersona != null) {
            log.debug("Persona resolved via default: {}", defaultPersona.id());
            return Optional.of(defaultPersona);
        }

        return Optional.empty();
    }

    /**
     * Forget the sticky persona for a conversation.
     */
    public void clearSticky(String conversationId) {
        stickyContext.remove(conversationId);
    }

    /**
     * Returns all registered personas.
     */
    public List<Persona> getAllPersonas() {
        return personas;
    }

    public Optional<Persona> findById(String id) {
        return personas.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    private Optional<String> safeLlmClassify(String message) {
        try {
            return llmClassifier.apply(message);
        } catch (Exception e) {
            log.warn("LLM classifier failed: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
