-- Tenant padrão canônico (RCHFLW-13).
--
-- Até aqui o motor gravava "SYSTEM" quando a execução não trazia tenant, enquanto a API resolvia a
-- requisição sem tenant como "__default__". A fila de aprovações filtra pelo tenant da requisição,
-- então um estado AWAITING_APPROVAL gravado como SYSTEM nunca aparecia na fila de ninguém (só sob
-- "all"). O motor agora grava "__default__" (DefaultTenant.ID); esta migração traz o dado antigo
-- para o mesmo id, para não haver dois "tenant padrão" no banco e os leitores por tenant
-- (fila, getStatesByTenant) enxergarem também o que foi gravado antes.
--
-- Migrar em vez de tolerar na leitura: um alias SYSTEM→__default__ teria de ser lembrado por
-- todo leitor que filtra por tenant (fila de aprovações, observabilidade, repositórios), e o
-- primeiro que esquecesse voltaria a esconder dado.
--
-- O NOT EXISTS protege a PK (tenant_id, flow_id): se o mesmo flow_id já existir sob __default__
-- (só aconteceria com um produtor que gravou os dois), a linha SYSTEM fica onde está em vez de
-- derrubar a migração — getState(flowId) continua lendo-a, pois não filtra por tenant.
UPDATE flow_states
   SET tenant_id = '__default__'
 WHERE tenant_id = 'SYSTEM'
   AND NOT EXISTS (SELECT 1
                     FROM flow_states d
                    WHERE d.tenant_id = '__default__'
                      AND d.flow_id = flow_states.flow_id);

UPDATE audit_logs
   SET tenant_id = '__default__'
 WHERE tenant_id = 'SYSTEM';
