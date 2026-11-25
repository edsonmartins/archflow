-- Tenant e convite dos usuários do login (RCHFLW-85).
--
-- A tela Admin › Usuários passou a operar sobre esta tabela — a mesma do login — em vez de um mapa
-- em memória semeado com dados de demonstração. Duas coisas faltavam aqui para isso:
--
-- 1. tenant_id: o convite é feito no workspace de um tenant e precisa criar acesso a ESSE tenant.
--    Sem a coluna, todo usuário entrava no tenant padrão (o token não tinha a claim tenantId). As
--    linhas existentes são as de quem já entra hoje, e hoje todos entram no tenant padrão: o
--    backfill com '__default__' (DefaultTenant.ID, como em V5_1_1/V6_12_2) só torna explícito o
--    que já acontece. O DEFAULT é removido em seguida: um INSERT que esquecesse a coluna deve
--    falhar, e não cair calado no tenant padrão.
-- 2. o convite pendente: o hash do segredo do link de aceite (o segredo só aparece uma vez, para o
--    administrador) e a validade. Um convidado ainda não tem senha — por isso password_hash deixa
--    de ser NOT NULL; ele também fica com enabled = FALSE até aceitar, então não entra.
--
-- Versão 5.1.2 para não ocupar o próximo número inteiro, que outro trabalho em paralelo pode estar
-- usando (aplicada fora de ordem em bancos que já têm a V6_x: spring.flyway.out-of-order no prod).
-- DDL portável (PostgreSQL e H2).
ALTER TABLE users ADD COLUMN tenant_id VARCHAR(64) DEFAULT '__default__' NOT NULL;
ALTER TABLE users ALTER COLUMN tenant_id DROP DEFAULT;

ALTER TABLE users ADD COLUMN invitation_token_hash VARCHAR(255);
ALTER TABLE users ADD COLUMN invitation_expires_at TIMESTAMP;

ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

CREATE INDEX idx_users_tenant ON users (tenant_id);
