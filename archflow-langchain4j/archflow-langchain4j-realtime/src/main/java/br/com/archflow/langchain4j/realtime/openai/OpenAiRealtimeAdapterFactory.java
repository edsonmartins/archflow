package br.com.archflow.langchain4j.realtime.openai;

import br.com.archflow.langchain4j.realtime.spi.RealtimeAdapter;
import br.com.archflow.langchain4j.realtime.spi.RealtimeAdapterFactory;

import java.util.Map;

/**
 * ServiceLoader factory that exposes {@link OpenAiRealtimeAdapter} under
 * the {@code "openai"} provider id. Registered via
 * {@code META-INF/services/br.com.archflow.langchain4j.realtime.spi.RealtimeAdapterFactory}.
 */
public class OpenAiRealtimeAdapterFactory implements RealtimeAdapterFactory {

    @Override
    public String providerId() {
        return OpenAiRealtimeAdapter.PROVIDER_ID;
    }

    @Override
    public RealtimeAdapter create(Map<String, Object> properties) {
        OpenAiRealtimeAdapter adapter = new OpenAiRealtimeAdapter();
        adapter.configure(properties);
        return adapter;
    }
}
