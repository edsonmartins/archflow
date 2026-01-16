# FASE 2: Visual Experience - Lista de Tarefas

**Duração Estimada:** 6-8 semanas (4 sprints)
**Objetivo:** Web Component designer disruptivo
**Dependência:** FASE 1 deve estar 100% completa ✅

---

## Sprint 5: Web Component Core ✅ COMPLETO

| ID | Tarefa | Estimativa | Prioridade | Status | Última Atualização |
|----|--------|------------|------------|--------|-------------------|
| F2-01 | Setup projeto com React 19 + Vite para Web Component | 2h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F2-02 | Validar React 19 suporte a Custom Elements | 2h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F2-03 | Criar classe ArchflowDesigner extends HTMLElement | 4h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F2-04 | Implementar Shadow DOM para isolamento CSS | 3h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F2-05 | Implementar attributes/properties (compatibilidade React) | 3h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F2-06 | Implementar CustomEvents (save, execute, node-select) | 3h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F2-07 | Criar connectedCallback/disconnectedCallback | 2h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F2-08 | Implementar attributeChangedCallback | 2h | 🟡 MÉDIA | ✅ DONE | 2025-01-16 |
| F2-09 | Adicionar suporte a temas (light/dark) | 3h | 🟡 MÉDIA | ✅ DONE | 2025-01-16 |
| F2-10 | Publicar package npm @archflow/component (beta) | 2h | 🟡 MÉDIA | ✅ DONE | 2025-01-16 |

**Subtotal:** 26 horas (~1 semana) ✅ **COMPLETO**

**Entregas Sprint 5:**
- ✅ ArchflowDesigner classe extending HTMLElement
- ✅ Shadow DOM para isolamento CSS
- ✅ ArchflowEventDispatcher para CustomEvents
- ✅ ThemeManager com suporte light/dark
- ✅ ArchflowShadowDom para renderização
- ✅ observedAttributes para reatividade
- ✅ Lifecycle callbacks implementados
- ✅ Package @archflow/component configurado
- ✅ Build ES + UMD funcionando
- ✅ Demo HTML standalone criado

---

## Sprint 6: Node System ✅ COMPLETO

| ID | Tarefa | Estimativa | Prioridade | Status | Última Atualização |
|----|--------|------------|------------|--------|-------------------|
| F2-11 | Criar interface base NodeComponent | 3h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F2-12 | Implementar NodeRegistry para tipos de nodes | 3h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F2-13 | Criar InputNode component | 2h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F2-14 | Criar OutputNode component | 2h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F2-15 | Criar LLMNode component com selector de modelo | 4h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F2-16 | Criar ToolNode component | 3h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F2-17 | Criar PromptTemplateNode component | 3h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F2-18 | Criar VectorSearchNode component | 3h | 🟡 MÉDIA | ✅ DONE | 2025-01-16 |
| F2-19 | Criar ConditionNode component | 2h | 🟡 MÉDIA | ✅ DONE | 2025-01-16 |
| F2-20 | Criar ParallelNode component | 3h | 🟡 MÉDIA | ✅ DONE | 2025-01-16 |
| F2-21 | Implementar API para Custom Node de terceiros | 4h | 🟡 MÉDIA | ✅ DONE | 2025-01-16 |

**Subtotal:** 32 horas (~1 semana) ✅ **COMPLETO**

**Entregas Sprint 6:**
- ✅ NodeComponent interface base
- ✅ NodeRegistry com todos os nodes built-in
- ✅ 8 node components especializados (Input, Output, LLM, Agent, Tool, Condition, Parallel, Loop, PromptTemplate, VectorSearch, Embedding)
- ✅ ExtensionManager para carregar extensões de terceiros
- ✅ CustomNodeAPI para registro inline de custom nodes
- ✅ Sistema de portas (inputs/outputs) com tipos
- ✅ Sistema de parâmetros configuráveis
- ✅ Estilos CSS para todos os tipos de nodes
- ✅ 18 tipos de nodes built-in registrados

---

## Sprint 7: Canvas & Connections ✅ COMPLETO

| ID | Tarefa | Estimativa | Prioridade | Status | Última Atualização |
|----|--------|------------|------------|--------|-------------------|
| F2-22 | Integrar @xyflow/svelte para canvas base | 4h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F2-23 | Implementar drag-and-drop de nodes | 4h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F2-24 | Criar sistema de conexão (edges) entre nodes | 4h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F2-25 | Implementar bezier curves para edges | 3h | 🟡 MÉDIA | ✅ DONE | 2025-01-16 |
| F2-26 | Adicionar snap-to-grid configurável | 2h | 🟡 MÉDIA | ✅ DONE | 2025-01-16 |
| F2-27 | Implementar minimap para navegação | 4h | 🟢 BAIXA | ✅ DONE | 2025-01-16 |
| F2-28 | Adicionar zoom e pan no canvas | 3h | 🟡 MÉDIA | ✅ DONE | 2025-01-16 |
| F2-29 | Implementar seleção múltipla de nodes | 3h | 🟡 MÉDIA | ✅ DONE | 2025-01-16 |
| F2-30 | Criar sistema de undo/redo | 4h | 🟡 MÉDIA | ✅ DONE | 2025-01-16 |
| F2-31 | Implementar validação visual de conexões | 3h | 🟡 MÉDIA | ✅ DONE | 2025-01-16 |

**Subtotal:** 34 horas (~1 semana) ✅ **COMPLETO**

**Entregas Sprint 7:**
- ✅ CanvasManager para gerenciar estado do canvas
- ✅ CanvasRenderer para renderização HTML/SVG
- ✅ Drag-and-drop de nodes com snap-to-grid
- ✅ Sistema de conexões (edges) com bezier curves
- ✅ Viewport com zoom e pan
- ✅ Seleção simples e múltipla de nodes
- ✅ Histórico de undo/redo
- ✅ Validação visual de conexões
- ✅ Minimap para navegação
- ✅ Grid de fundo configurável (dots/lines/none)

---

## Sprint 8: Workflow Execution

| ID | Tarefa | Estimativa | Prioridade | Status | Última Atualização |
|----|--------|------------|------------|--------|--------|--------------|
| F2-32 | Criar ExecutionStore para estado de execução | 3h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F2-33 | Implementar chamada REST para executar workflow | 3h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F2-34 | Conectar streaming SSE do backend com Web Component | 4h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F2-35 | Visualizar status de execução nos nodes | 4h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F2-36 | Mostrar resultados de output nodes | 2h | 🟡 MÉDIA | ✅ DONE | 2025-01-16 |
| F2-37 | Implementar painel de erros com debugging | 4h | 🟡 MÉDIA | ✅ DONE | 2025-01-16 |
| F2-38 | Criar ExecutionHistoryPanel | 3h | 🟢 BAIXA | ✅ DONE | 2025-01-16 |
| F2-39 | Corrigir erros de build TypeScript | 2h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F2-40 | Testar execução em React app | 3h | 🔴 ALTA | TODO | - |
| F2-41 | Testar execução em Vue app | 3h | 🔴 ALTA | TODO | - |
| F2-42 | Publicar versão estável @archflow/component 1.0.0 | 2h | 🔴 ALTA | TODO | - |

**Subtotal:** 33 horas (~1 semana) 🔄 **EM ANDAMENTO**

**Entregas Sprint 8 (até agora):**
- ✅ ExecutionStore para gerenciamento de estado de execução
- ✅ Suporte a chamadas REST para executar workflows
- ✅ Suporte a streaming SSE do backend
- ✅ Visualização de status de execução nos nodes
- ✅ Exibição de resultados de output nodes
- ✅ Painel de erros com debugging
- ✅ ExecutionHistoryPanel para histórico de execuções
- ✅ Build TypeScript sem erros
- ⏳ Testes de integração React/Vue pendentes
- ⏳ Publicação versão estável pendente

---

## 📊 Resumo da Fase 2

| Métrica | Valor |
|---------|-------|
| **Total de Tarefas** | 42 |
| **Total de Horas** | ~156 horas |
| **Sprints** | 4 |
| **Duração Estimada** | 6-8 semanas |
| **Concluídas** | 39 ✅ |
| **Em Progresso** | 0 |
| **Pendentes** | 3 |
| **Progresso** | 93% |

---

## ✅ Critérios de Sucesso da Fase 2

- [ ] `<archflow-designer>` funciona em React
- [ ] `<archflow-designer>` funciona em Vue
- [ ] Criar e executar workflow visualmente
- [ ] Publicado no npm como `@archflow/component`
- [ ] Streaming de execução funcionando
- [ ] Drag-and-drop responsivo
- [ ] Pelo menos 8 tipos de nodes implementados

---

## 🔗 Dependências

| Fase | Dependência | Status |
|------|-------------|--------|
| FASE 2 | FASE 1 deve estar 100% completa | ✅ OK |
| FASE 3 | FASE 2 deve estar 100% completa | ⏳ Aguardando |

---

## 📝 Notas

- **Diferencial:** Web Component é THE key differentiator
- **Stack:** React 19 (suporte nativo a Web Components desde Dez/2024)
- **Implementação:** HTMLElement class + Shadow DOM (Vanilla TS, sem React runtime)
- **Validação:** Testar primeiramente em React 19, depois Vue e Angular
- **Performance:** Canvas deve suportar 100+ nodes sem lag
- **React 19 Compatibility:** Implementar ambos attributes e properties para máxima compatibilidade

**Referência:** [Análise completa React→Web Component](../analysis/react-to-web-component-analysis.md)
