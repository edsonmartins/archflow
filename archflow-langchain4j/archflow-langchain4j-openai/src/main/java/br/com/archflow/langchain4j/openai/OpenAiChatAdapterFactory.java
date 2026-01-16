package br.com.archflow.langchain4j.openai;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.langchain4j.core.spi.LangChainAdapterFactory;

import java.util.Map;

/**
 * Factory para criação do adapter de chat OpenAI via SPI.
 *
 * <p>Esta factory é registrada via ServiceLoader (META-INF/services) e permite
 * a descoberta automática do adapter OpenAI pelo Archflow.
 */
public class OpenAiChatAdapterFactory implements LangChainAdapterFactory {

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
        return "chat".equalsIgnoreCase(type);
    }
}
