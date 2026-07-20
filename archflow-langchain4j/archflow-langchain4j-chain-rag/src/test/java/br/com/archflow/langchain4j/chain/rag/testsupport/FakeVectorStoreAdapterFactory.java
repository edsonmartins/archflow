package br.com.archflow.langchain4j.chain.rag.testsupport;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.langchain4j.core.spi.LangChainAdapterFactory;
import dev.langchain4j.store.embedding.EmbeddingStore;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * SPI factory de teste que registra o provider {@code "fake-vectorstore"} e
 * devolve um adapter que também é um {@link EmbeddingStore} (mock), satisfazendo
 * a checagem {@code instanceof EmbeddingStore} do RagChainAdapter sem tocar rede.
 *
 * <p>Registrada em {@code src/test/resources/META-INF/services}.
 */
public class FakeVectorStoreAdapterFactory implements LangChainAdapterFactory {

    @Override
    public String getProvider() {
        return "fake-vectorstore";
    }

    @Override
    public LangChainAdapter createAdapter(Map<String, Object> properties) {
        return mock(LangChainAdapter.class, withSettings().extraInterfaces(EmbeddingStore.class));
    }

    @Override
    public boolean supports(String type) {
        return "vectorstore".equals(type);
    }
}
