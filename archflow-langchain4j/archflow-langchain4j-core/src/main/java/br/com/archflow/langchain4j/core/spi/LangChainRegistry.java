package br.com.archflow.langchain4j.core.spi;

import java.util.HashSet;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry central para descoberta e criação de adapters
 */
public class LangChainRegistry {
    private static final Map<String, LangChainAdapterFactory> factories = new ConcurrentHashMap<>();
    
    static {
        // Carrega factories via SPI
        ServiceLoader<LangChainAdapterFactory> loader = ServiceLoader.load(LangChainAdapterFactory.class);
        for (LangChainAdapterFactory factory : loader) {
            factories.put(factory.getProvider(), factory);
        }
    }
    
    public static LangChainAdapter createAdapter(String provider, String type, Map<String, Object> properties) {
        LangChainAdapterFactory factory = factories.get(provider);
        if (factory == null) {
            throw new IllegalArgumentException("Provider not found: " + provider);
        }
        
        if (!factory.supports(type)) {
            throw new IllegalArgumentException(
                String.format("Provider %s does not support type %s", provider, type));
        }
        
        return factory.createAdapter(properties);
    }

    /**
     * Verifica se um provider está disponível
     */
    public static boolean hasProvider(String provider) {
        return factories.containsKey(provider);
    }

    /**
     * Lista todos os providers disponíveis
     */
    public static Set<String> getAvailableProviders() {
        return new HashSet<>(factories.keySet());
    }
}