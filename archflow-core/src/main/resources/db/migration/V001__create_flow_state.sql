CREATE TABLE flow_states (
    tenant_id       VARCHAR(36)  NOT NULL,
    flow_id         VARCHAR(36)  NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    current_step_id VARCHAR(64),
    variables       JSON,
    metrics         JSON,
    error           JSON,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, flow_id)
);

CREATE INDEX idx_flow_states_tenant_status ON flow_states (tenant_id, status);

CREATE TABLE audit_logs (
    id              BIGSERIAL    PRIMARY KEY,
    tenant_id       VARCHAR(36)  NOT NULL,
    flow_id         VARCHAR(36)  NOT NULL,
    state_snapshot  JSON,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_logs_tenant_flow ON audit_logs (tenant_id, flow_id);
