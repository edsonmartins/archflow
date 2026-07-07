-- Persistência durável de conversas, mensagens e versões de prompt.
-- Usada por br.com.archflow.conversation.persistence.jdbc.*
-- Colunas de metadados são TEXT (JSON serializado) para portabilidade
-- entre PostgreSQL, H2 e MySQL.

CREATE TABLE conversations (
    id          VARCHAR(64)  PRIMARY KEY,
    tenant_id   VARCHAR(64)  NOT NULL,
    user_id     VARCHAR(64)  NOT NULL,
    channel     VARCHAR(32)  NOT NULL,
    status      VARCHAR(32)  NOT NULL,
    persona     VARCHAR(128),
    metadata    TEXT,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL
);

CREATE INDEX idx_conversations_tenant ON conversations (tenant_id, updated_at);
CREATE INDEX idx_conversations_tenant_user ON conversations (tenant_id, user_id);

CREATE TABLE conversation_messages (
    id              VARCHAR(64)  PRIMARY KEY,
    conversation_id VARCHAR(64)  NOT NULL,
    tenant_id       VARCHAR(64)  NOT NULL,
    sender_type     VARCHAR(16)  NOT NULL,
    message_type    VARCHAR(16)  NOT NULL,
    content         TEXT         NOT NULL,
    media_url       TEXT,
    metadata        TEXT,
    created_at      TIMESTAMP    NOT NULL
);

CREATE INDEX idx_conv_messages ON conversation_messages (tenant_id, conversation_id, created_at);

CREATE TABLE prompt_versions (
    tenant_id   VARCHAR(64)  NOT NULL,
    prompt_id   VARCHAR(128) NOT NULL,
    version     INT          NOT NULL,
    template    TEXT         NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP    NOT NULL,
    PRIMARY KEY (tenant_id, prompt_id, version)
);

CREATE INDEX idx_prompt_versions_active ON prompt_versions (tenant_id, prompt_id, active);
