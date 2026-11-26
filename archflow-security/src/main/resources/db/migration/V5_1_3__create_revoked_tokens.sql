-- Tokens e sessões revogados antes da expiração (logout, refresh token já consumido).
--
-- Até aqui a revogação vivia só em memória, por réplica: o logout feito numa instância não valia
-- nas outras, e o refresh token nunca era revogado — continuava renovando a sessão depois do logout,
-- quantas vezes se quisesse. O JdbcTokenBlacklist grava aqui, no mesmo banco dos usuários e das
-- chaves de API, para que toda réplica veja a mesma revogação.
--
-- token_hash: SHA-256 (base64) da chave — o token em si, ou a chave derivada do id da sessão de
--   login. Nunca o token em claro. A chave primária é o que dá um único vencedor ao consumo
--   concorrente do mesmo refresh token (rotação).
-- expires_at: epoch millis, e não TIMESTAMP: a comparação com "agora" é feita por cada réplica, e
--   um TIMESTAMP sem fuso dependeria do fuso da JVM de quem gravou e de quem lê.
--
-- Versão 5.1.3 pelo mesmo motivo da 5.1.2 (aplicada fora de ordem onde a V6_x já existe).
-- DDL portável (PostgreSQL e H2).
CREATE TABLE revoked_tokens (
    token_hash VARCHAR(64) PRIMARY KEY,
    expires_at BIGINT      NOT NULL
);
CREATE INDEX idx_revoked_tokens_expires ON revoked_tokens (expires_at);
