# ADR-0004 — ExecutionContext: interface com mutação deprecated em vez do record imutável da RFC-005

- **Status:** Aceito
- **Data:** 2026-06-11
- **Decisores:** Edson Martins
- **Contexto de origem:** RFC-005 v2 (VendaX) especifica `ExecutionContext`
  como `record` com campos imutáveis (`tenantId`, `userId`, `sessionId`,
  `requestId`, `variables` não-modificável). A auditoria de 11/06/2026
  apontou o desvio: o código mantém uma **interface** com `set(String,Object)`
  e `setState(FlowState)` mutáveis.

## Decisão

Manter `ExecutionContext` como interface, com o caminho imutável como API
recomendada e o caminho mutável formalmente deprecated:

1. `set()` e `setState()` permanecem na interface **anotados `@Deprecated`**
   — removê-los agora quebraria todos os steps/plugins existentes.
2. Código novo usa exclusivamente `withVariable()`/`withState()` (cópia
   imutável) e `ImmutableExecutionContext`, que lança
   `UnsupportedOperationException` nos métodos mutáveis.
3. Os campos multi-tenant (`getTenantId()` etc.) têm defaults na interface
   com fallback `"SYSTEM"`, como a RFC pede.
4. A remoção definitiva dos métodos deprecated fica para o próximo major
   (breaking change), quando os plugins do catálogo migrarem.

## Consequências

- **Risco residual:** um step mal-comportado ainda PODE mutar um contexto
  mutável compartilhado entre threads. Mitigação: o engine entrega
  `ImmutableExecutionContext` nos caminhos concorrentes (fan-out/parallel),
  onde a mutação falha alto em vez de vazar dados entre tenants.
- **Conformidade RFC-005:** parcial por escolha consciente — o contrato
  imutável existe e é o recomendado; a forma (interface vs record) difere
  para preservar compatibilidade.
