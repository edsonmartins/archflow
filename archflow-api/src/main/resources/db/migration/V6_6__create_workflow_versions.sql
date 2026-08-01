-- Immutable workflow publication snapshots and environment pointers.
CREATE TABLE workflow_versions (
    id          VARCHAR(64)  PRIMARY KEY,
    workflow_id VARCHAR(64)  NOT NULL,
    version_no  INTEGER      NOT NULL,
    document    TEXT         NOT NULL,
    comment     VARCHAR(512),
    created_at  TIMESTAMP    NOT NULL,
    CONSTRAINT uq_workflow_version_no UNIQUE (workflow_id, version_no)
);

CREATE INDEX idx_workflow_versions_workflow_id
    ON workflow_versions (workflow_id, version_no);

CREATE TABLE workflow_deployments (
    workflow_id VARCHAR(64) NOT NULL,
    environment VARCHAR(32) NOT NULL,
    version_id  VARCHAR(64) NOT NULL,
    deployed_at TIMESTAMP   NOT NULL,
    PRIMARY KEY (workflow_id, environment),
    CONSTRAINT fk_workflow_deployment_version
        FOREIGN KEY (version_id) REFERENCES workflow_versions (id)
);

CREATE INDEX idx_workflow_deployments_version_id
    ON workflow_deployments (version_id);
