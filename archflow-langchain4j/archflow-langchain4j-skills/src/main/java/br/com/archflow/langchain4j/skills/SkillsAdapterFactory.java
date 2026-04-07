package br.com.archflow.langchain4j.skills;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.langchain4j.core.spi.LangChainAdapterFactory;

import java.util.Map;

/**
 * Factory for creating SkillsAdapter instances via SPI.
 *
 * <p>Registered via ServiceLoader (META-INF/services) for automatic discovery.
 */
public class SkillsAdapterFactory implements LangChainAdapterFactory {

    @Override
    public String getProvider() {
        return "skills";
    }

    @Override
    public LangChainAdapter createAdapter(Map<String, Object> properties) {
        SkillsAdapter adapter = new SkillsAdapter();
        adapter.configure(properties);
        return adapter;
    }

    @Override
    public boolean supports(String type) {
        return "skills".equalsIgnoreCase(type);
    }
}
