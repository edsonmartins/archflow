-- Laços de tool-calling suspensos aguardando decisão humana.
--
-- O transcript do laço vivia só em heap: um crash no meio de um raciocínio
-- multi-turno perdia tudo, e não havia como suspender para aprovação DENTRO de
-- uma conversa — só entre steps do grafo (flow_states).
--
-- O estado inteiro vai numa coluna JSON de propósito: ele é a fronteira de
-- durabilidade do laço, e é exatamente o objeto que um orquestrador durável
-- externo persistiria no lugar desta tabela. Espalhá-lo em colunas amarraria o
-- schema à forma interna do laço e tornaria essa troca uma migração.
CREATE TABLE mcp_agent_states (
    run_id       VARCHAR(64)  PRIMARY KEY,
    tenant_id    VARCHAR(36)  NOT NULL,
    -- Id da solicitação de aprovação. É por ele que o decisor chega (ele não
    -- conhece o run_id), então precisa de índice próprio.
    request_id   VARCHAR(64),
    state        JSON         NOT NULL,
    suspended_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_mcp_agent_states_request ON mcp_agent_states (request_id);
CREATE INDEX idx_mcp_agent_states_tenant ON mcp_agent_states (tenant_id, suspended_at);
