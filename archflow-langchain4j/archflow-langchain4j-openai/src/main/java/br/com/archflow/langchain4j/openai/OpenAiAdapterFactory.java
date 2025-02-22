package br.com.archflow.langchain4j.openai;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.langchain4j.core.spi.LangChainAdapterFactory;

import java.util.Map;

/**
 * Factory para o adapter OpenAI
 */
public class OpenAiAdapterFactory implements LangChainAdapterFactory {
    @Override
    public String getProvider() {
        return "openai";
    }

    @Override
    public LangChainAdapter createAdapter(Map<String, Object> properties) {
        OpenAiChatAdapter adapter = new OpenAiChatAdapter();
        adapter.configure(properties);
        return adapter;
    }

    @Override
    public boolean supports(String type) {
        return "chat".equals(type) || "model".equals(type);
    }
}