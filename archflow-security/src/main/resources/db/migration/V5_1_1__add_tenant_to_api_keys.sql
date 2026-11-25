-- Tenant das chaves de API (RCHFLW-84/86).
--
-- Uma chave de API passa a autenticar requisições (Authorization: Bearer <chave>), e a identidade
-- da requisição é a da chave: o dono e o tenant em que ela foi criada. Sem a coluna, a chave não
-- tinha tenant, e a requisição ou cairia num tenant escolhido pelo cliente (X-Tenant-Id) ou em
-- nenhum.
--
-- Dado legado: até aqui nenhuma chave autenticava nada (validateApiKey não tinha chamador), a
-- criação pela UI sempre falhava (RCHFLW-83) e a API de chaves tomava o dono do cabeçalho
-- X-User-Id, escolhido pelo cliente (RCHFLW-86). Uma linha existente, então, tem dono não
-- verificado e nenhum tenant. Atribuir-lhe um tenant e deixá-la ativa transformaria uma credencial
-- dormente numa credencial viva num tenant que ninguém escolheu para ela — e invisível, porque o
-- dono dela não é o de nenhum login. Por isso toda linha anterior a esta migração é DESATIVADA
-- (enabled = FALSE: o ApiKeyService.authenticate a recusa com 401 "revoked") e precisa ser
-- recriada pela tela ou por POST /api/apikeys, onde dono e tenant vêm do token.
--
-- O tenant '__default__' (DefaultTenant.ID, como em V6_12_2) só preenche a coluna NOT NULL dessas
-- linhas desativadas; não dá acesso a nada. O DEFAULT é removido em seguida: um INSERT que
-- esquecesse a coluna deve falhar, e não cair calado no tenant padrão.
--
-- Versão 5.1.1 para não ocupar o próximo número inteiro, que outro trabalho em paralelo pode estar
-- usando (aplicada fora de ordem em bancos que já têm a V6_x: spring.flyway.out-of-order no prod).
-- DDL portável (PostgreSQL e H2).
UPDATE api_keys SET enabled = FALSE;

ALTER TABLE api_keys ADD COLUMN tenant_id VARCHAR(64) DEFAULT '__default__' NOT NULL;
ALTER TABLE api_keys ALTER COLUMN tenant_id DROP DEFAULT;

CREATE INDEX idx_api_keys_tenant_owner ON api_keys (tenant_id, owner_id);
