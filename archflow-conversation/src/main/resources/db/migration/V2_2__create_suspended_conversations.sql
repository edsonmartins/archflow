-- Estado de suspend/resume das conversas (conversas aguardando input humano).
-- Distinto do histórico (conversations/conversation_messages): guarda o token de
-- retomada, o formulário e o contexto para que a retomada sobreviva a restart.
-- SQL ANSI portável (PostgreSQL, H2, MySQL); form/context como JSON em TEXT.

CREATE TABLE suspended_conversations (
    conversation_id       VARCHAR(64)  PRIMARY KEY,
    resume_token          VARCHAR(128) NOT NULL UNIQUE,
    workflow_id           VARCHAR(64),
    workflow_execution_id VARCHAR(64),
    form                  TEXT,
    context               TEXT,
    status                VARCHAR(32)  NOT NULL,
    priority              INT          NOT NULL DEFAULT 0,
    created_at            TIMESTAMP    NOT NULL,
    expires_at            TIMESTAMP
);
CREATE INDEX idx_suspended_token ON suspended_conversations (resume_token);
CREATE INDEX idx_suspended_expires ON suspended_conversations (expires_at);
