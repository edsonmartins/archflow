package br.com.archflow.api.admin.store;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link GlobalConfigStore} em memória — default quando a persistência JDBC
 * está desligada. Config admin é recuperável (o controller recai nos defaults
 * embutidos), então isto gera apenas um WARN do
 * {@code ProductionReadinessGuard}, não uma violação fatal.
 */
public class InMemoryGlobalConfigStore implements GlobalConfigStore {

    private final Map<String, String> values = new ConcurrentHashMap<>();

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(values.get(key));
    }

    @Override
    public void put(String key, String value) {
        values.put(key, value);
    }
}
