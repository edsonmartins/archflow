package br.com.archflow.langchain4j.skills;

import java.io.IOException;
import java.util.List;

/**
 * Interface for loading skills from a source.
 */
public interface SkillLoader {

    /**
     * Loads all skills from the configured source.
     *
     * @return List of loaded skills
     * @throws IOException if loading fails
     */
    List<Skill> loadSkills() throws IOException;
}
