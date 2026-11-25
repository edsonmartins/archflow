-- Consumidor da fila de invocações de agente (RCHFLW-80).
--
-- A fila tinha produtores (invoke, chat, gatilhos, eventos) e nenhum consumidor: toda invocação
-- respondia ACCEPTED e ficava na tabela para sempre. O consumidor precisa de duas coisas que a
-- tabela não tinha.
--
-- 1) Lease. O poll antigo apagava a linha no ato de retirar; se o processo caísse antes de terminar,
--    a invocação sumia. Agora a linha é reivindicada (claimed_by/claimed_until) e só sai no fim, depois
--    de o resultado gravado. Um lease vencido — o consumidor morreu — devolve a linha a quem
--    reivindicar depois, em qualquer réplica.
ALTER TABLE agent_invocations ADD COLUMN claimed_by VARCHAR(128);
ALTER TABLE agent_invocations ADD COLUMN claimed_until TIMESTAMP;

-- 2) Resultado consultável por requestId. É o que o Agent lab consulta depois de Invocar/Enviar; sem
--    ele, "aceito" era tudo o que alguém chegava a saber. attempts conta as execuções iniciadas: um
--    RUNNING encontrado ao reivindicar de novo é uma execução que morreu no meio.
CREATE TABLE agent_invocation_results (
    request_id  VARCHAR(64)  PRIMARY KEY,
    tenant_id   VARCHAR(128) NOT NULL,
    agent_id    VARCHAR(128) NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    output      TEXT,
    error       TEXT,
    attempts    INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL,
    started_at  TIMESTAMP,
    finished_at TIMESTAMP
);

CREATE INDEX idx_agent_invocation_results_finished ON agent_invocation_results (finished_at);
