-- executionPaths não tinha coluna: o JdbcStateRepository montava o FlowState
-- sem ele na leitura e o descartava na escrita. Um fluxo com ramos paralelos
-- retomado após restart perdia a árvore de execução — o que a OrchestrateStep
-- materializa justamente para o resume e a inspeção conseguirem enxergá-la.
ALTER TABLE flow_states ADD COLUMN execution_paths JSON;

-- getStatesByStatus (fila de aprovações humanas) filtra por status entre TODOS
-- os tenants, então o índice composto (tenant_id, status) não a atende.
CREATE INDEX idx_flow_states_status ON flow_states (status);
