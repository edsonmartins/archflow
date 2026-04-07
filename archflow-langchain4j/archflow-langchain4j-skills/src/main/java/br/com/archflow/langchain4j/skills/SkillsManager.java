package br.com.archflow.langchain4j.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages skill lifecycle: loading, activation, deactivation, and listing.
 *
 * <p>Skills are loaded from a {@link SkillLoader} and stored in a registry.
 * The LLM can activate skills via the adapter, which makes their full content
 * available in the conversation context.
 *
 * <h3>Example:</h3>
 * <pre>{@code
 * SkillsManager manager = new SkillsManager();
 * manager.loadFrom(new FileSystemSkillLoader(Path.of("skills/")));
 *
 * manager.activateSkill("docx");
 * Skill active = manager.getActiveSkill("docx").orElseThrow();
 * }</pre>
 */
public class SkillsManager {

    private static final Logger log = LoggerFactory.getLogger(SkillsManager.class);

    private final Map<String, Skill> registry;
    private final Set<String> activeSkills;

    public SkillsManager() {
        this.registry = new ConcurrentHashMap<>();
        this.activeSkills = ConcurrentHashMap.newKeySet();
    }

    /**
     * Loads skills from a loader and registers them.
     *
     * @param loader The skill loader
     * @return Number of skills loaded
     * @throws IOException if loading fails
     */
    public int loadFrom(SkillLoader loader) throws IOException {
        List<Skill> skills = loader.loadSkills();
        for (Skill skill : skills) {
            registry.put(skill.name(), skill);
        }
        log.info("Loaded {} skills", skills.size());
        return skills.size();
    }

    /**
     * Registers a single skill.
     */
    public void register(Skill skill) {
        registry.put(skill.name(), skill);
        log.debug("Registered skill: {}", skill.name());
    }

    /**
     * Activates a skill, making its full content available.
     *
     * @param name The skill name
     * @return The activated skill
     * @throws IllegalArgumentException if skill not found
     */
    public Skill activateSkill(String name) {
        Skill skill = registry.get(name);
        if (skill == null) {
            throw new IllegalArgumentException("Skill not found: " + name);
        }
        activeSkills.add(name);
        log.info("Activated skill: {}", name);
        return skill;
    }

    /**
     * Deactivates a skill.
     */
    public void deactivateSkill(String name) {
        activeSkills.remove(name);
        log.debug("Deactivated skill: {}", name);
    }

    /**
     * Gets an active skill by name.
     */
    public Optional<Skill> getActiveSkill(String name) {
        // Note: check-then-get is not atomic, but returning empty
        // when a concurrent deactivation occurs is semantically correct
        if (!activeSkills.contains(name)) return Optional.empty();
        return Optional.ofNullable(registry.get(name));
    }

    /**
     * Gets a registered skill by name (active or not).
     */
    public Optional<Skill> getSkill(String name) {
        return Optional.ofNullable(registry.get(name));
    }

    /**
     * Lists all registered skills (name + description only).
     */
    public List<Skill> listSkills() {
        return List.copyOf(registry.values());
    }

    /**
     * Lists currently active skills.
     */
    public List<Skill> listActiveSkills() {
        return activeSkills.stream()
                .map(registry::get)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Returns a formatted string of available skills suitable for LLM system messages.
     */
    public String formatAvailableSkills() {
        if (registry.isEmpty()) return "No skills available.";
        StringBuilder sb = new StringBuilder("Available skills:\n");
        for (Skill skill : registry.values()) {
            sb.append(skill.formatForLlm()).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * Checks if a skill is currently active.
     */
    public boolean isActive(String name) {
        return activeSkills.contains(name);
    }

    /**
     * Returns the number of registered skills.
     */
    public int size() {
        return registry.size();
    }

    /**
     * Clears all skills and active state.
     */
    public void clear() {
        registry.clear();
        activeSkills.clear();
    }

    /**
     * Gets a skill resource by skill name and resource name.
     *
     * @param skillName The skill name
     * @param resourceName The resource name
     * @return The resource content, or empty if not found
     */
    public Optional<SkillResource> getSkillResource(String skillName, String resourceName) {
        return getSkill(skillName)
                .flatMap(skill -> skill.resources().stream()
                        .filter(r -> r.name().equals(resourceName))
                        .findFirst());
    }
}
