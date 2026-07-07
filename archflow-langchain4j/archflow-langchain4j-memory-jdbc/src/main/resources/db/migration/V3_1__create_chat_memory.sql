CREATE TABLE chat_messages (
    id          BIGSERIAL    PRIMARY KEY,
    tenant_id   VARCHAR(36)  NOT NULL,
    session_id  VARCHAR(64)  NOT NULL,
    role        VARCHAR(16)  NOT NULL,
    content     TEXT         NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_chat_messages_tenant_session ON chat_messages (tenant_id, session_id);
