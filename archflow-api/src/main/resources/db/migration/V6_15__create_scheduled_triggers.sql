-- Registro dos gatilhos agendados (RCHFLW-187).
--
-- O registro vivia só num mapa em memória, enquanto o job do Quartz ficava no banco (JobStoreTX,
-- V6_1): depois de um restart a tela listava nada e os jobs continuavam disparando sem achar o
-- gatilho. O JobDataMap do Quartz guarda só o id; nome, cron, tenant, agente, payload, ativo e o
-- último disparo moram aqui.
--
-- updated_at é a versão da configuração: o PUT com If-Match só grava se ela ainda for a lida
-- (RCHFLW-191). O último disparo (last_*) não muda a versão — um disparo do cron no meio de uma
-- edição não é uma edição concorrente.
--
-- Usado por br.com.archflow.api.triggers.store.JdbcScheduledTriggerStore. DDL portável (PostgreSQL e H2).
CREATE TABLE scheduled_triggers (
    id              VARCHAR(64)  PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    cron_expression VARCHAR(255) NOT NULL,
    tenant_id       VARCHAR(128) NOT NULL,
    agent_id        VARCHAR(255) NOT NULL,
    payload         TEXT         NOT NULL,
    enabled         BOOLEAN      NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    last_fired_at   TIMESTAMP,
    last_error      TEXT,
    last_request_id VARCHAR(64),
    last_status     VARCHAR(16)
);

CREATE INDEX idx_scheduled_triggers_tenant ON scheduled_triggers (tenant_id);
