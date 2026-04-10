package br.com.archflow.langchain4j.openrouter;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.langchain4j.core.spi.LangChainAdapterFactory;

import java.util.Map;

/**
 * Factory SPI para criação do adapter OpenRouter.
 *
 * <p>Registrada via {@code META-INF/services/br.com.archflow.langchain4j.core.spi.LangChainAdapterFactory}
 * para descoberta automática pelo ArchFlow.
 */
public class OpenRouterChatAdapterFactory implements LangChainAdapterFactory {

    @Override
    public String getProvider() {
        return "openrouter";
    }

    @Override
    public LangChainAdapter createAdapter(Map<String, Object> properties) {
        OpenRouterChatAdapter adapter = new OpenRouterChatAdapter();
        adapter.configure(properties);
        return adapter;
    }

    @Override
    public boolean supports(String type) {
        return "chat".equalsIgnoreCase(type);
    }
}
