-- Configuração admin da plataforma (catálogo de modelos LLM, defaults de
-- plano, feature toggles) — key-value com valor JSON serializado (Jackson).
-- Usado por br.com.archflow.api.admin.store.JdbcGlobalConfigStore.
CREATE TABLE global_config (
    config_key   VARCHAR(128) PRIMARY KEY,
    config_value TEXT         NOT NULL,
    updated_at   TIMESTAMP    NOT NULL
);
