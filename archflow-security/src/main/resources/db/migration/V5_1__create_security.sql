-- Security module durable schema: users, their roles, and API keys.
-- ANSI-portable DDL (PostgreSQL, H2, MySQL). Applied automatically by Flyway
-- when on the classpath, or manually (see docs/development/production-persistence.md).

CREATE TABLE users (
    id                      VARCHAR(64)  PRIMARY KEY,
    username                VARCHAR(128) NOT NULL UNIQUE,
    email                   VARCHAR(255),
    password_hash           VARCHAR(255) NOT NULL,
    first_name              VARCHAR(128),
    last_name               VARCHAR(128),
    enabled                 BOOLEAN      NOT NULL DEFAULT TRUE,
    account_non_expired     BOOLEAN      NOT NULL DEFAULT TRUE,
    account_non_locked      BOOLEAN      NOT NULL DEFAULT TRUE,
    credentials_non_expired BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at              TIMESTAMP    NOT NULL,
    updated_at              TIMESTAMP    NOT NULL,
    last_login_at           TIMESTAMP
);
CREATE INDEX idx_users_email ON users (email);

CREATE TABLE user_roles (
    user_id   VARCHAR(64) NOT NULL,
    role_name VARCHAR(64) NOT NULL,
    PRIMARY KEY (user_id, role_name)
);
CREATE INDEX idx_user_roles_user ON user_roles (user_id);

CREATE TABLE api_keys (
    id           VARCHAR(64)  PRIMARY KEY,
    key_id       VARCHAR(64)  NOT NULL UNIQUE,
    key_secret   VARCHAR(255) NOT NULL,
    name         VARCHAR(128),
    owner_id     VARCHAR(64)  NOT NULL,
    scopes       TEXT,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    expires_at   TIMESTAMP,
    last_used_at TIMESTAMP,
    created_at   TIMESTAMP    NOT NULL,
    created_by   VARCHAR(64)
);
CREATE INDEX idx_api_keys_owner ON api_keys (owner_id);
