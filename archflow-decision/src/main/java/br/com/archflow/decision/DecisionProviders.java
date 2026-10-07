package br.com.archflow.decision;

import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Os provedores disponíveis, por id. Nasce com os embutidos ({@link HttpDecisionProvider},
 * {@link RulesDecisionProvider}) e os que o classpath declara por SPI; o hospedeiro soma os seus.
 */
public final class DecisionProviders {

    private final Map<String, DecisionProvider> porId = new ConcurrentHashMap<>();

    private DecisionProviders() {
    }

    /** Os embutidos mais os descobertos por SPI. */
    public static DecisionProviders withDefaults() {
        DecisionProviders providers = new DecisionProviders();
        providers.register(new HttpDecisionProvider());
        providers.register(new RulesDecisionProvider());
        for (DecisionProvider descoberto : ServiceLoader.load(DecisionProvider.class)) {
            providers.register(descoberto);
        }
        return providers;
    }

    /** Vazio, para teste. */
    public static DecisionProviders empty() {
        return new DecisionProviders();
    }

    /** Registra (ou substitui) um provedor. */
    public DecisionProviders register(DecisionProvider provider) {
        porId.put(provider.id(), provider);
        return this;
    }

    public Optional<DecisionProvider> find(String id) {
        return Optional.ofNullable(id == null ? null : porId.get(id));
    }
}
