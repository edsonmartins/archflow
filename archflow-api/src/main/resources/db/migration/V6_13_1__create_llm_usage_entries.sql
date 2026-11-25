-- Consumo de modelo medido, por execução e tenant (RCHFLW-75).
--
-- O ContadorDeUso somava tokens e custo só no VendaX e no invoke, e o total saía no resultado da
-- chamada e mais nada: o detalhe da execução não tinha uso e as telas de uso mostravam números
-- fixos no código. Cada linha é o total de UM contador (id = ContadorDeUso.execucaoId): relatar o
-- mesmo contador de novo substitui a linha, nunca soma outra vez.
--
-- cost_cents/cost_reais são os da tabela de preços (archflow.llm.precos) — NULL quando não há preço
-- declarado, nunca zero. declared_cost_usd é o que o provedor declarou (OpenRouter), NULL quando
-- algum turno não declarou. recorded_at (o primeiro relato) decide o mês.
--
-- Versão 6.13.1 para não ocupar o próximo número inteiro, que outro trabalho em paralelo pode estar
-- usando (spring.flyway.out-of-order no prod). DDL portável (PostgreSQL e H2).
CREATE TABLE llm_usage_entries (
    id                VARCHAR(64)  PRIMARY KEY,
    tenant_id         VARCHAR(64)  NOT NULL,
    flow_execution_id VARCHAR(128),
    origin            VARCHAR(32)  NOT NULL,
    model             VARCHAR(512),
    input_tokens      BIGINT,
    output_tokens     BIGINT,
    cost_cents        BIGINT,
    cost_reais        DOUBLE PRECISION,
    declared_cost_usd DOUBLE PRECISION,
    turns             INTEGER,
    recorded_at       TIMESTAMP    NOT NULL,
    updated_at        TIMESTAMP    NOT NULL
);
CREATE INDEX idx_llm_usage_tenant_recorded ON llm_usage_entries (tenant_id, recorded_at);
CREATE INDEX idx_llm_usage_flow_execution ON llm_usage_entries (tenant_id, flow_execution_id);
