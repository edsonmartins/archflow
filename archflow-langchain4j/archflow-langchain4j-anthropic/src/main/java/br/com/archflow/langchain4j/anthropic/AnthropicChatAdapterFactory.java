package br.com.archflow.langchain4j.anthropic;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.langchain4j.core.spi.LangChainAdapterFactory;

import java.util.Map;

/**
 * Factory para o adapter Anthropic (Claude)
 */
public class AnthropicChatAdapterFactory implements LangChainAdapterFactory {
    @Override
    public String getProvider() {
        return "anthropic";
    }

    @Override
    public LangChainAdapter createAdapter(Map<String, Object> properties) {
        AnthropicChatAdapter adapter = new AnthropicChatAdapter();
        adapter.configure(properties);
        return adapter;
    }

    @Override
    public boolean supports(String type) {
        return "chat".equals(type) || "model".equals(type);
    }
}
