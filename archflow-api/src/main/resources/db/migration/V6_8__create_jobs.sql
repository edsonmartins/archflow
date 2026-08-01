CREATE TABLE jobs (
    id                 VARCHAR(64)  PRIMARY KEY,
    tenant_id          VARCHAR(128) NOT NULL,
    workspace_id       VARCHAR(128),
    type               VARCHAR(128) NOT NULL,
    status             VARCHAR(32)  NOT NULL,
    payload            TEXT         NOT NULL,
    progress           INTEGER      NOT NULL DEFAULT 0,
    message            VARCHAR(1024),
    attempt            INTEGER      NOT NULL DEFAULT 0,
    max_attempts       INTEGER      NOT NULL DEFAULT 3,
    idempotency_key    VARCHAR(255),
    cancel_requested   BOOLEAN      NOT NULL DEFAULT FALSE,
    worker_id          VARCHAR(128),
    error              TEXT,
    created_at         TIMESTAMP    NOT NULL,
    started_at         TIMESTAMP,
    completed_at       TIMESTAMP,
    updated_at         TIMESTAMP    NOT NULL,
    CONSTRAINT uq_jobs_idempotency UNIQUE (tenant_id, type, idempotency_key)
);

CREATE INDEX idx_jobs_claim ON jobs (status, created_at);
CREATE INDEX idx_jobs_tenant_created ON jobs (tenant_id, created_at);
