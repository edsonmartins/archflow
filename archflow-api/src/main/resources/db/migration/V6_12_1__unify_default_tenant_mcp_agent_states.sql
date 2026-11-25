-- Tenant padrão canônico (RCHFLW-13), para os laços de agente suspensos.
--
-- JdbcMcpAgentStateStore gravava "SYSTEM" na coluna quando o estado não trazia tenant; agora grava
-- "__default__" (DefaultTenant.ID), o mesmo id que a API usa para a requisição sem tenant. A coluna
-- é o filtro da fila (findPendingByTenant) e o JSON é o que a fila mostra (McpAgentState.tenantId),
-- então os dois mudam juntos — trocar só a coluna faria a pendência aparecer na fila certa dizendo
-- pertencer a outro tenant.
--
-- Versão 6.12.1 (e não 6.13) de propósito: fica logo depois da última migração do módulo sem ocupar
-- o próximo número inteiro, que outro trabalho em paralelo pode estar usando.
UPDATE mcp_agent_states
   SET tenant_id = '__default__',
       state = jsonb_set(state::jsonb, '{tenantId}', '"__default__"'::jsonb, true)::json
 WHERE tenant_id = 'SYSTEM';
