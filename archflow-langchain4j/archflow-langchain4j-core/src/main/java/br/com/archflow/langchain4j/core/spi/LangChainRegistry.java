package br.com.archflow.langchain4j.core.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry central para descoberta e criação de adapters.
 *
 * <p>Factory discovery uses the <em>initialization-on-demand holder</em>
 * idiom: the JVM guarantees the enclosing class is loaded lazily and
 * its {@code <clinit>} runs exactly once under a class-init lock, so
 * the fully-populated {@code factories} map is safely published to all
 * threads — no double-check needed, and {@code ServiceLoader.load}
 * (which is itself not documented as thread-safe) is invoked exactly
 * once even if multiple threads first touch the registry concurrently.
 */
public class LangChainRegistry {

    private LangChainRegistry() {}

    private static final class Holder {
        /**
         * {@code providerId → list of factories}. Multiple factories can
         * share the same provider id (e.g. {@code "openai"} registers a
         * chat factory and an embedding factory independently); keeping
         * a list per id preserves both instead of the last-write-wins
         * behaviour a plain Map would cause.
         */
        private static final Map<String, List<LangChainAdapterFactory>> FACTORIES = loadFactories();

        private static Map<String, List<LangChainAdapterFactory>> loadFactories() {
            Map<String, List<LangChainAdapterFactory>> map = new ConcurrentHashMap<>();
            ServiceLoader<LangChainAdapterFactory> loader =
                    ServiceLoader.load(LangChainAdapterFactory.class);
            for (LangChainAdapterFactory factory : loader) {
                map.computeIfAbsent(factory.getProvider(), k -> new ArrayList<>()).add(factory);
            }
            return map;
        }
    }

    private static Map<String, List<LangChainAdapterFactory>> factories() {
        return Holder.FACTORIES;
    }

    public static LangChainAdapter createAdapter(String provider, String type, Map<String, Object> properties) {
        List<LangChainAdapterFactory> candidates = factories().get(provider);
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("Provider not found: " + provider);
        }

        LangChainAdapterFactory factory = null;
        for (LangChainAdapterFactory f : candidates) {
            if (f.supports(type)) { factory = f; break; }
        }
        if (factory == null) {
            throw new IllegalArgumentException(
                String.format("Provider %s does not support type %s", provider, type));
        }

        return factory.createAdapter(properties);
    }

    /**
     * Verifica se um provider está disponível
     */
    public static boolean hasProvider(String provider) {
        List<LangChainAdapterFactory> list = factories().get(provider);
        return list != null && !list.isEmpty();
    }

    /**
     * Lista todos os providers disponíveis
     */
    public static Set<String> getAvailableProviders() {
        return new HashSet<>(factories().keySet());
    }

    /**
     * Lista os providers que suportam um determinado {@code type}
     * (ex.: {@code "chat"}, {@code "embedding"}, {@code "memory"},
     * {@code "vectorstore"}, {@code "chain"}). A descoberta é via SPI
     * e usa {@link LangChainAdapterFactory#supports(String)} de cada
     * factory registrado. Um mesmo provider id pode aparecer em mais
     * de um tipo (openai chat + openai embedding, por exemplo).
     */
    public static Set<String> getProvidersOfType(String type) {
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, List<LangChainAdapterFactory>> e : factories().entrySet()) {
            for (LangChainAdapterFactory f : e.getValue()) {
                if (f.supports(type)) { out.add(e.getKey()); break; }
            }
        }
        return out;
    }

    /** Returns all factories registered under {@code provider}, empty list when unknown. */
    public static List<LangChainAdapterFactory> getFactoriesForProvider(String provider) {
        List<LangChainAdapterFactory> list = factories().get(provider);
        return list == null ? Collections.emptyList() : Collections.unmodifiableList(list);
    }
}