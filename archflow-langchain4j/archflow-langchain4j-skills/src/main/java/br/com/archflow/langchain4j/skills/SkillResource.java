package br.com.archflow.langchain4j.skills;

import java.util.Objects;

/**
 * A resource associated with a skill (template, example file, etc.).
 *
 * @param name Resource identifier
 * @param mimeType MIME type (e.g., "text/plain", "application/json")
 * @param content The resource content as a string
 */
public record SkillResource(
        String name,
        String mimeType,
        String content
) {
    public SkillResource {
        Objects.requireNonNull(name, "name is required");
        Objects.requireNonNull(content, "content is required");
        if (mimeType == null) mimeType = "text/plain";
    }

    public static SkillResource of(String name, String content) {
        return new SkillResource(name, "text/plain", content);
    }
}
