package br.com.archflow.langchain4j.skills;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("FileSystemSkillLoader")
class FileSystemSkillLoaderTest {

    @Test
    @DisplayName("should load skills from classpath resources")
    void shouldLoadSkillsFromClasspath() throws Exception {
        Path skillsDir = Path.of(getClass().getClassLoader().getResource("skills").toURI());
        FileSystemSkillLoader loader = new FileSystemSkillLoader(skillsDir);

        List<Skill> skills = loader.loadSkills();

        assertThat(skills).hasSizeGreaterThanOrEqualTo(2);
        assertThat(skills).extracting(Skill::name).contains("docx", "calculator");
    }

    @Test
    @DisplayName("should parse YAML front matter correctly")
    void shouldParseFrontMatter() throws Exception {
        Path skillsDir = Path.of(getClass().getClassLoader().getResource("skills").toURI());
        FileSystemSkillLoader loader = new FileSystemSkillLoader(skillsDir);
        List<Skill> skills = loader.loadSkills();

        Skill docx = skills.stream().filter(s -> s.name().equals("docx")).findFirst().orElseThrow();
        assertThat(docx.description()).isEqualTo("Edit and review Word documents using tracked changes");
        assertThat(docx.content()).contains("tracked changes");
    }

    @Test
    @DisplayName("should load resources from skill directory")
    void shouldLoadResources() throws Exception {
        Path skillsDir = Path.of(getClass().getClassLoader().getResource("skills").toURI());
        FileSystemSkillLoader loader = new FileSystemSkillLoader(skillsDir);
        List<Skill> skills = loader.loadSkills();

        Skill docx = skills.stream().filter(s -> s.name().equals("docx")).findFirst().orElseThrow();
        assertThat(docx.resources()).hasSizeGreaterThanOrEqualTo(1);
        assertThat(docx.resources()).extracting(SkillResource::name).contains("template.txt");
    }

    @Test
    @DisplayName("should return empty list for non-existent directory")
    void shouldReturnEmptyForNonExistentDir() throws IOException {
        FileSystemSkillLoader loader = new FileSystemSkillLoader(Path.of("/nonexistent/path"));
        List<Skill> skills = loader.loadSkills();

        assertThat(skills).isEmpty();
    }

    @Test
    @DisplayName("should handle SKILL.md without front matter")
    void shouldHandleSkillWithoutFrontMatter(@TempDir Path tempDir) throws Exception {
        Path skillDir = tempDir.resolve("simple");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "Just plain instructions without front matter");

        FileSystemSkillLoader loader = new FileSystemSkillLoader(tempDir);
        List<Skill> skills = loader.loadSkills();

        assertThat(skills).hasSize(1);
        assertThat(skills.get(0).name()).isEqualTo("simple"); // Falls back to directory name
        assertThat(skills.get(0).content()).contains("Just plain instructions");
    }

    @Test
    @DisplayName("should split front matter correctly")
    void shouldSplitFrontMatter() {
        FileSystemSkillLoader loader = new FileSystemSkillLoader(Path.of("."));
        String raw = "---\nname: test\ndescription: A test\n---\nBody content here";

        String[] parts = loader.splitFrontMatter(raw);

        assertThat(parts[0]).contains("name: test");
        assertThat(parts[1]).isEqualTo("Body content here");
    }

    @Test
    @DisplayName("should parse front matter key-value pairs")
    void shouldParseFrontMatterKeyValues() {
        FileSystemSkillLoader loader = new FileSystemSkillLoader(Path.of("."));
        var result = loader.parseFrontMatter("name: docx\ndescription: Edit documents");

        assertThat(result).containsEntry("name", "docx");
        assertThat(result).containsEntry("description", "Edit documents");
    }

    @Test
    @DisplayName("should skip directories without SKILL.md")
    void shouldSkipDirsWithoutSkillMd(@TempDir Path tempDir) throws Exception {
        Path withSkill = tempDir.resolve("with-skill");
        Path withoutSkill = tempDir.resolve("without-skill");
        Files.createDirectories(withSkill);
        Files.createDirectories(withoutSkill);
        Files.writeString(withSkill.resolve("SKILL.md"), "---\nname: test\ndescription: Test\n---\nContent");
        Files.writeString(withoutSkill.resolve("README.md"), "Not a skill");

        FileSystemSkillLoader loader = new FileSystemSkillLoader(tempDir);
        List<Skill> skills = loader.loadSkills();

        assertThat(skills).hasSize(1);
        assertThat(skills.get(0).name()).isEqualTo("test");
    }
}
