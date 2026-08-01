-- Tenant-scoped metadata for blobs in the configured ObjectStorage.
CREATE TABLE stored_files (
    id            VARCHAR(64)  PRIMARY KEY,
    tenant_id     VARCHAR(128) NOT NULL,
    workspace_id  VARCHAR(128),
    original_name VARCHAR(512) NOT NULL,
    content_type  VARCHAR(255) NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    sha256        VARCHAR(64)  NOT NULL,
    storage_key   VARCHAR(512) NOT NULL UNIQUE,
    created_at    TIMESTAMP    NOT NULL
);

CREATE INDEX idx_stored_files_tenant_workspace_created
    ON stored_files (tenant_id, workspace_id, created_at);
