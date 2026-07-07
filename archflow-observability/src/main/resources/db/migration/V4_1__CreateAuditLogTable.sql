-- Trilha de auditoria (segurança/compliance): eventos de autenticação,
-- autorização, CRUD, execuções de workflow/agent/tool/LLM e eventos de segurança.
-- DDL PostgreSQL, consumida por JdbcAuditRepository. O id é fornecido pela
-- aplicação (UUID em VARCHAR(36)), coerente com AuditEvent.getId().

CREATE TABLE af_audit_log (
    id             VARCHAR(36)  PRIMARY KEY,
    timestamp      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    action_code    VARCHAR(50)  NOT NULL,
    success        BOOLEAN      NOT NULL DEFAULT TRUE,
    user_id        VARCHAR(100),
    username       VARCHAR(100),
    resource_type  VARCHAR(50),
    resource_id    VARCHAR(255),
    error_message  TEXT,
    ip_address     VARCHAR(45),
    user_agent     VARCHAR(500),
    session_id     VARCHAR(100),
    trace_id       VARCHAR(64),
    context        TEXT,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_timestamp ON af_audit_log (timestamp DESC);
CREATE INDEX idx_audit_user ON af_audit_log (user_id, timestamp DESC);
CREATE INDEX idx_audit_action ON af_audit_log (action_code, timestamp DESC);
CREATE INDEX idx_audit_resource ON af_audit_log (resource_type, resource_id, timestamp DESC);
CREATE INDEX idx_audit_trace ON af_audit_log (trace_id);
CREATE INDEX idx_audit_success ON af_audit_log (success, timestamp DESC);
