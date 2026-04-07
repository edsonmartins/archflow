package br.com.archflow.langchain4j.skills;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.model.engine.ExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Adapter for LangChain4j Skills integration.
 *
 * <p>Exposes skills as operations that can be invoked by the archflow agent system.
 * Follows the same adapter pattern as OpenAI/Anthropic adapters.
 *
 * <p>Supported operations:
 * <ul>
 *   <li>{@code list_skills} — Returns available skills (name + description)</li>
 *   <li>{@code activate_skill} — Activates a skill and returns its full content</li>
 *   <li>{@code deactivate_skill} — Deactivates a currently active skill</li>
 *   <li>{@code read_skill_resource} — Reads a resource from a skill</li>
 *   <li>{@code get_active_skills} — Returns currently active skills</li>
 * </ul>
 *
 * <p>Configuration properties:
 * <ul>
 *   <li>{@code skills.directory} — Path to the skills directory (required for file system loading)</li>
 * </ul>
 */
public class SkillsAdapter implements LangChainAdapter {

    private static final Logger log = LoggerFactory.getLogger(SkillsAdapter.class);

    private SkillsManager manager;
    private Map<String, Object> config;

    @Override
    public void validate(Map<String, Object> properties) {
        if (properties == null) {
            throw new IllegalArgumentException("Properties cannot be null");
        }
        // skills.directory is optional — skills can be registered programmatically
    }

    @Override
    public void configure(Map<String, Object> properties) {
        validate(properties);
        this.config = properties;
        this.manager = new SkillsManager();

        String skillsDir = (String) properties.get("skills.directory");
        if (skillsDir != null && !skillsDir.isBlank()) {
            try {
                FileSystemSkillLoader loader = new FileSystemSkillLoader(Path.of(skillsDir));
                int count = manager.loadFrom(loader);
                log.info("Skills adapter configured with {} skills from {}", count, skillsDir);
            } catch (Exception e) {
                log.error("Failed to load skills from {}: {}", skillsDir, e.getMessage());
            }
        }
    }

    @Override
    public Object execute(String operation, Object input, ExecutionContext context) throws Exception {
        if (manager == null) {
            throw new IllegalStateException("Adapter not configured. Call configure() first.");
        }

        return switch (operation) {
            case "list_skills" -> executeListSkills();
            case "activate_skill" -> executeActivateSkill(input);
            case "deactivate_skill" -> executeDeactivateSkill(input);
            case "read_skill_resource" -> executeReadResource(input);
            case "get_active_skills" -> executeGetActiveSkills();
            default -> throw new IllegalArgumentException("Unsupported operation: " + operation);
        };
    }

    private Object executeListSkills() {
        return manager.listSkills().stream()
                .map(s -> Map.of("name", s.name(), "description", s.description()))
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private Object executeActivateSkill(Object input) {
        String skillName = extractSkillName(input);
        Skill skill = manager.activateSkill(skillName);
        return Map.of(
                "name", skill.name(),
                "description", skill.description(),
                "content", skill.content(),
                "resources", skill.resources().stream().map(SkillResource::name).toList()
        );
    }

    private Object executeDeactivateSkill(Object input) {
        String skillName = extractSkillName(input);
        manager.deactivateSkill(skillName);
        return Map.of("deactivated", skillName);
    }

    @SuppressWarnings("unchecked")
    private Object executeReadResource(Object input) {
        if (!(input instanceof Map)) {
            throw new IllegalArgumentException("Input must be a map with 'skill' and 'resource' keys");
        }
        Map<String, Object> params = (Map<String, Object>) input;
        String skillName = (String) params.get("skill");
        String resourceName = (String) params.get("resource");

        if (skillName == null || resourceName == null) {
            throw new IllegalArgumentException("Both 'skill' and 'resource' parameters are required");
        }

        return manager.getSkillResource(skillName, resourceName)
                .map(r -> Map.of("name", r.name(), "mimeType", r.mimeType(), "content", r.content()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Resource not found: " + resourceName + " in skill " + skillName));
    }

    private Object executeGetActiveSkills() {
        return manager.listActiveSkills().stream()
                .map(s -> Map.of("name", s.name(), "description", s.description()))
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private String extractSkillName(Object input) {
        if (input instanceof String s) return s;
        if (input instanceof Map) {
            Object name = ((Map<String, Object>) input).get("name");
            if (name instanceof String s) return s;
        }
        throw new IllegalArgumentException("Input must be a skill name (String) or map with 'name' key");
    }

    /**
     * Returns the underlying SkillsManager for programmatic access.
     */
    public SkillsManager getManager() {
        return manager;
    }

    @Override
    public void shutdown() {
        if (manager != null) {
            manager.clear();
        }
        this.config = null;
        this.manager = null;
    }
}
