-- Skills ativas por tenant (RCHFLW-66).
--
-- O switch de Admin > Skills guardava o estado só em memória (um mapa no controller e o set global do
-- SkillsManager, que nem separava tenants): tudo voltava a inativo a cada restart. O conteúdo das
-- skills continua vindo do diretório archflow.skills.directory, igual para a instância; aqui fica só a
-- escolha de cada tenant. Uma linha por skill ativa — sem linha, a skill está inativa, que é o estado
-- de quem nunca a ligou.
--
-- Usado por br.com.archflow.api.skills.store.JdbcSkillActivationStore. DDL portável (PostgreSQL e H2).
CREATE TABLE tenant_active_skills (
    tenant_id    VARCHAR(128) NOT NULL,
    skill_name   VARCHAR(255) NOT NULL,
    activated_at TIMESTAMP    NOT NULL,
    PRIMARY KEY (tenant_id, skill_name)
);
