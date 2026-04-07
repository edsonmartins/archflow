package br.com.archflow.langchain4j.skills;

import java.util.List;
import java.util.Objects;

/**
 * A skill — a self-contained behavioral instruction bundle with metadata.
 *
 * <p>Skills follow the <a href="https://agentskills.io">Agent Skills specification</a>.
 * Each skill consists of a name, description, content (instructions), and optional resources.
 *
 * <p>Skills are typically loaded from SKILL.md files with YAML front matter:
 * <pre>
 * ---
 * name: docx
 * description: Edit and review Word documents
 * ---
 * [Detailed instructions here]
 * </pre>
 *
 * @param name Unique skill identifier
 * @param description Human-readable description (used by LLM for tool selection)
 * @param content The full instruction content
 * @param resources Optional associated resources (files, templates, etc.)
 */
public record Skill(
        String name,
        String description,
        String content,
        List<SkillResource> resources
) {
    public Skill {
        Objects.requireNonNull(name, "name is required");
        Objects.requireNonNull(description, "description is required");
        Objects.requireNonNull(content, "content is required");
        resources = resources == null ? List.of() : List.copyOf(resources);
    }

    /**
     * Creates a skill without resources.
     */
    public static Skill of(String name, String description, String content) {
        return new Skill(name, description, content, List.of());
    }

    /**
     * Returns a summary suitable for inclusion in LLM system messages.
     */
    public String formatForLlm() {
        return String.format("- **%s**: %s", name, description);
    }
}
