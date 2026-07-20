package br.com.archflow.langchain4j.chain.rag.testsupport;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.langchain4j.core.spi.LangChainAdapterFactory;
import dev.langchain4j.model.embedding.EmbeddingModel;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * SPI factory de teste que registra o provider {@code "fake-embedding"} e
 * devolve um adapter que também é um {@link EmbeddingModel} (mock), satisfazendo
 * a checagem {@code instanceof EmbeddingModel} do RagChainAdapter sem tocar rede.
 *
 * <p>Registrada em {@code src/test/resources/META-INF/services}.
 */
public class FakeEmbeddingAdapterFactory implements LangChainAdapterFactory {

    @Override
    public String getProvider() {
        return "fake-embedding";
    }

    @Override
    public LangChainAdapter createAdapter(Map<String, Object> properties) {
        return mock(LangChainAdapter.class, withSettings().extraInterfaces(EmbeddingModel.class));
    }

    @Override
    public boolean supports(String type) {
        return "embedding".equals(type);
    }
}
