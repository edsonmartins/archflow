package br.com.archflow.api.admin.store;

import java.util.Optional;

/**
 * Key-value store da configuração admin da plataforma (catálogo de modelos
 * LLM, defaults de plano, feature toggles) usada por
 * {@code GlobalConfigControllerImpl}.
 *
 * <p>Os valores são documentos JSON serializados (Jackson) — o contrato é um
 * key-value simples para que a implementação durável seja uma tabela
 * {@code global_config} portável (PostgreSQL/H2).
 *
 * <p>Implementações:
 * <ul>
 *   <li>{@link InMemoryGlobalConfigStore} — default (dev/test); perde tudo no
 *       restart, comportamento histórico do controller;</li>
 *   <li>{@link JdbcGlobalConfigStore} — durável, ligada por
 *       {@code archflow.persistence.jdbc.enabled=true} (migration
 *       {@code V6_4__create_global_config.sql}).</li>
 * </ul>
 */
public interface GlobalConfigStore {

    /** O valor serializado da chave, ou vazio quando nunca gravado. */
    Optional<String> get(String key);

    /** Cria/substitui o valor serializado da chave. */
    void put(String key, String value);
}
