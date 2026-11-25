-- Tenant dos workflows do designer e das execuções deles (RCHFLW-73).
--
-- Até aqui workflow_documents e workflow_executions não tinham dono: ao entrar no tenant B, o usuário
-- via, editava, executava e apagava os fluxos e as execuções do tenant A. JdbcWorkflowRuntimeStore
-- passa a filtrar toda leitura por tenant_id.
--
-- Dado legado: toda linha existente foi gravada sem tenant, isto é, pelo tenant de quem não declarou
-- nenhum. Ela vai para o tenant padrão canônico, '__default__' (DefaultTenant.ID) — o mesmo id que a
-- API resolve para a requisição sem tenant —, e continua visível exatamente onde sempre apareceu.
-- O JSON das execuções antigas não tem a chave "tenantId"; o store a preenche a partir da coluna na
-- leitura, então não é preciso reescrever o TEXT aqui.
--
-- O DEFAULT existe só para preencher as linhas antigas e é removido em seguida: um INSERT que
-- esquecesse a coluna deve falhar, e não cair calado no tenant padrão.
--
-- Versões e implantações (V6_6) não ganham coluna: pendem de um workflow cujo id é chave primária,
-- e o store checa o dono do workflow antes de lê-las ou alterá-las.
--
-- Versão 6.12.2 pelo mesmo motivo da 6.12.1: não ocupar o próximo número inteiro, que outro trabalho
-- em paralelo pode estar usando. DDL portável (PostgreSQL e H2).
ALTER TABLE workflow_documents ADD COLUMN tenant_id VARCHAR(64) DEFAULT '__default__' NOT NULL;
ALTER TABLE workflow_documents ALTER COLUMN tenant_id DROP DEFAULT;

ALTER TABLE workflow_executions ADD COLUMN tenant_id VARCHAR(64) DEFAULT '__default__' NOT NULL;
ALTER TABLE workflow_executions ALTER COLUMN tenant_id DROP DEFAULT;

CREATE INDEX idx_workflow_documents_tenant_id ON workflow_documents (tenant_id);
CREATE INDEX idx_workflow_executions_tenant_workflow ON workflow_executions (tenant_id, workflow_id);
