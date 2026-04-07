package br.com.archflow.langchain4j.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Loads skills from the file system.
 *
 * <p>Expects a directory structure where each subdirectory contains a SKILL.md file
 * with YAML front matter (name, description) followed by the instruction content:
 *
 * <pre>
 * skills/
 * ├── docx/
 * │   ├── SKILL.md
 * │   └── template.docx (optional resource)
 * └── calculator/
 *     └── SKILL.md
 * </pre>
 *
 * <p>SKILL.md format:
 * <pre>
 * ---
 * name: docx
 * description: Edit and review Word documents
 * ---
 * [Instructions content]
 * </pre>
 */
public class FileSystemSkillLoader implements SkillLoader {

    private static final Logger log = LoggerFactory.getLogger(FileSystemSkillLoader.class);
    private static final String SKILL_FILE = "SKILL.md";
    private static final String FRONT_MATTER_DELIMITER = "---";

    private final Path skillsDirectory;

    public FileSystemSkillLoader(Path skillsDirectory) {
        this.skillsDirectory = Objects.requireNonNull(skillsDirectory, "skillsDirectory is required");
    }

    @Override
    public List<Skill> loadSkills() throws IOException {
        if (!Files.isDirectory(skillsDirectory)) {
            log.warn("Skills directory does not exist: {}", skillsDirectory);
            return List.of();
        }

        List<Skill> skills = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(skillsDirectory)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                Path skillFile = dir.resolve(SKILL_FILE);
                if (Files.exists(skillFile)) {
                    try {
                        Skill skill = parseSkillFile(skillFile, dir);
                        skills.add(skill);
                        log.info("Loaded skill: {} from {}", skill.name(), dir.getFileName());
                    } catch (IOException e) {
                        log.error("Failed to load skill from {}: {}", dir, e.getMessage());
                    }
                }
            });
        }
        return skills;
    }

    /**
     * Parses a single SKILL.md file into a Skill object.
     */
    Skill parseSkillFile(Path skillFile, Path skillDir) throws IOException {
        String raw = Files.readString(skillFile);
        String[] parts = splitFrontMatter(raw);
        Map<String, String> metadata = parseFrontMatter(parts[0]);
        String content = parts[1].trim();

        String name = metadata.getOrDefault("name", skillDir.getFileName().toString());
        String description = metadata.getOrDefault("description", "");

        // Load resources (non-SKILL.md files in the directory)
        List<SkillResource> resources = loadResources(skillDir);

        return new Skill(name, description, content, resources);
    }

    /**
     * Splits content into front matter and body.
     */
    String[] splitFrontMatter(String raw) {
        String trimmed = raw.trim();
        if (!trimmed.startsWith(FRONT_MATTER_DELIMITER)) {
            return new String[]{"", trimmed};
        }

        int secondDelimiter = trimmed.indexOf(FRONT_MATTER_DELIMITER, FRONT_MATTER_DELIMITER.length());
        if (secondDelimiter < 0) {
            return new String[]{"", trimmed};
        }

        String frontMatter = trimmed.substring(FRONT_MATTER_DELIMITER.length(), secondDelimiter).trim();
        String body = trimmed.substring(secondDelimiter + FRONT_MATTER_DELIMITER.length()).trim();
        return new String[]{frontMatter, body};
    }

    /**
     * Parses simple YAML key-value pairs from front matter.
     */
    Map<String, String> parseFrontMatter(String frontMatter) {
        Map<String, String> result = new LinkedHashMap<>();
        if (frontMatter.isEmpty()) return result;

        for (String line : frontMatter.split("\n")) {
            int colonIdx = line.indexOf(':');
            if (colonIdx > 0) {
                String key = line.substring(0, colonIdx).trim();
                String value = line.substring(colonIdx + 1).trim();
                result.put(key, value);
            }
        }
        return result;
    }

    private List<SkillResource> loadResources(Path skillDir) throws IOException {
        List<SkillResource> resources = new ArrayList<>();
        try (Stream<Path> files = Files.list(skillDir)) {
            files.filter(Files::isRegularFile)
                    .filter(f -> !f.getFileName().toString().equals(SKILL_FILE))
                    .forEach(f -> {
                        try {
                            String content = Files.readString(f);
                            String mimeType = Files.probeContentType(f);
                            resources.add(new SkillResource(
                                    f.getFileName().toString(),
                                    mimeType != null ? mimeType : "application/octet-stream",
                                    content));
                        } catch (IOException e) {
                            log.warn("Failed to load resource {}: {}", f, e.getMessage());
                        }
                    });
        }
        return resources;
    }
}
