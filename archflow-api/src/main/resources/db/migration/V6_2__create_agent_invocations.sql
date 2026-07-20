-- Fila durável de invocações de agente.
-- Usada por br.com.archflow.api.queue.JdbcAgentInvocationQueue.
-- payload é TEXT (JSON serializado) para portabilidade PostgreSQL/H2/MySQL.
CREATE TABLE agent_invocations (
    id                  VARCHAR(64)  PRIMARY KEY,
    tenant_id           VARCHAR(128) NOT NULL,
    agent_id            VARCHAR(128) NOT NULL,
    payload             TEXT,
    parent_execution_id VARCHAR(64),
    recursion_depth     INT          NOT NULL DEFAULT 0,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- poll() consome sempre a mais antiga (FIFO)
CREATE INDEX idx_agent_invocations_created_at ON agent_invocations (created_at, id);
