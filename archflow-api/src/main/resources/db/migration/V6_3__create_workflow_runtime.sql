-- Runtime durável dos workflows do designer (documentos + execuções).
-- Usado por br.com.archflow.api.web.workflow.JdbcWorkflowRuntimeStore.
-- document/record são TEXT (JSON serializado) para portabilidade PostgreSQL/H2.
CREATE TABLE workflow_documents (
    id         VARCHAR(64) PRIMARY KEY,
    document   TEXT        NOT NULL,
    updated_at TIMESTAMP   NOT NULL
);

CREATE TABLE workflow_executions (
    id          VARCHAR(64) PRIMARY KEY,
    workflow_id VARCHAR(64) NOT NULL,
    record      TEXT        NOT NULL,
    updated_at  TIMESTAMP   NOT NULL
);

-- listagem filtrada por workflow e ordenação/limpeza por recência
CREATE INDEX idx_workflow_executions_workflow_id ON workflow_executions (workflow_id);
CREATE INDEX idx_workflow_executions_updated_at ON workflow_executions (updated_at);
