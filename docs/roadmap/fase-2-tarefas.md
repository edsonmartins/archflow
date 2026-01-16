# FASE 2: Visual Experience - Lista de Tarefas

**Duração Estimada:** 6-8 semanas (4 sprints)
**Objetivo:** Web Component designer disruptivo
**Dependência:** FASE 1 deve estar 100% completa

---

## Sprint 5: Web Component Core

| ID | Tarefa | Estimativa | Prioridade | Status | Última Atualização |
|----|--------|------------|------------|--------|-------------------|
| F2-01 | Setup projeto com React 19 + Vite para Web Component | 2h | 🔴 ALTA | TODO | - |
| F2-02 | Validar React 19 suporte a Custom Elements | 2h | 🔴 ALTA | TODO | - |
| F2-03 | Criar classe ArchflowDesigner extends HTMLElement | 4h | 🔴 ALTA | TODO | - |
| F2-04 | Implementar Shadow DOM para isolamento CSS | 3h | 🔴 ALTA | TODO | - |
| F2-05 | Implementar attributes/properties (compatibilidade React) | 3h | 🔴 ALTA | TODO | - |
| F2-06 | Implementar CustomEvents (save, execute, node-select) | 3h | 🔴 ALTA | TODO | - |
| F2-07 | Criar connectedCallback/disconnectedCallback | 2h | 🔴 ALTA | TODO | - |
| F2-08 | Implementar attributeChangedCallback | 2h | 🟡 MÉDIA | TODO | - |
| F2-09 | Adicionar suporte a temas (light/dark) | 3h | 🟡 MÉDIA | TODO | - |
| F2-10 | Publicar package npm @archflow/component (beta) | 2h | 🟡 MÉDIA | TODO | - |

**Subtotal:** 26 horas (~1 semana)

**Nota:** React 19 (Dez/2024) tem suporte nativo a Web Components. Ver análise completa em [docs/analysis/react-to-web-component-analysis.md](../analysis/react-to-web-component-analysis.md)

---

## Sprint 6: Node System

| ID | Tarefa | Estimativa | Prioridade | Status | Última Atualização |
|----|--------|------------|------------|--------|-------------------|
| F2-11 | Criar interface base NodeComponent | 3h | 🔴 ALTA | TODO | - |
| F2-12 | Implementar NodeRegistry para tipos de nodes | 3h | 🔴 ALTA | TODO | - |
| F2-13 | Criar InputNode component | 2h | 🔴 ALTA | TODO | - |
| F2-14 | Criar OutputNode component | 2h | 🔴 ALTA | TODO | - |
| F2-15 | Criar LLMNode component com selector de modelo | 4h | 🔴 ALTA | TODO | - |
| F2-16 | Criar ToolNode component | 3h | 🔴 ALTA | TODO | - |
| F2-17 | Criar PromptTemplateNode component | 3h | 🔴 ALTA | TODO | - |
| F2-18 | Criar VectorSearchNode component | 3h | 🟡 MÉDIA | TODO | - |
| F2-19 | Criar ConditionNode component | 2h | 🟡 MÉDIA | TODO | - |
| F2-20 | Criar ParallelNode component | 3h | 🟡 MÉDIA | TODO | - |
| F2-21 | Implementar API para Custom Node de terceiros | 4h | 🟡 MÉDIA | TODO | - |

**Subtotal:** 32 horas (~1 semana)

---

## Sprint 7: Canvas & Connections

| ID | Tarefa | Estimativa | Prioridade | Status | Última Atualização |
|----|--------|------------|------------|--------|-------------------|
| F2-22 | Integrar @xyflow/svelte para canvas base | 4h | 🔴 ALTA | TODO | - |
| F2-23 | Implementar drag-and-drop de nodes | 4h | 🔴 ALTA | TODO | - |
| F2-24 | Criar sistema de conexão (edges) entre nodes | 4h | 🔴 ALTA | TODO | - |
| F2-25 | Implementar bezier curves para edges | 3h | 🟡 MÉDIA | TODO | - |
| F2-26 | Adicionar snap-to-grid configurável | 2h | 🟡 MÉDIA | TODO | - |
| F2-27 | Implementar minimap para navegação | 4h | 🟢 BAIXA | TODO | - |
| F2-28 | Adicionar zoom e pan no canvas | 3h | 🟡 MÉDIA | TODO | - |
| F2-29 | Implementar seleção múltipla de nodes | 3h | 🟡 MÉDIA | TODO | - |
| F2-30 | Criar sistema de undo/redo | 4h | 🟡 MÉDIA | TODO | - |
| F2-31 | Implementar validação visual de conexões | 3h | 🟡 MÉDIA | TODO | - |

**Subtotal:** 34 horas (~1 semana)

---

## Sprint 8: Workflow Execution

| ID | Tarefa | Estimativa | Prioridade | Status | Última Atualização |
|----|--------|------------|------------|--------|--------|--------------|
| F2-32 | Criar ExecutionStore para estado de execução | 3h | 🔴 ALTA | TODO | - |
| F2-33 | Implementar chamada REST para executar workflow | 3h | 🔴 ALTA | TODO | - |
| F2-34 | Conectar streaming SSE do backend com Web Component | 4h | 🔴 ALTA | TODO | - |
| F2-35 | Visualizar status de execução nos nodes | 4h | 🔴 ALTA | TODO | - |
| F2-36 | Mostrar resultados de output nodes | 2h | 🟡 MÉDIA | TODO | - |
| F2-37 | Implementar painel de erros com debugging | 4h | 🟡 MÉDIA | TODO | - |
| F2-38 | Criar ExecutionHistoryPanel | 3h | 🟢 BAIXA | TODO | - |
| F2-39 | Testar execução em React app | 3h | 🔴 ALTA | TODO | - |
| F2-40 | Testar execução em Vue app | 3h | 🔴 ALTA | TODO | - |
| F2-41 | Publicar versão estável @archflow/component 1.0.0 | 2h | 🔴 ALTA | TODO | - |

**Subtotal:** 31 horas (~1 semana)

---

## 📊 Resumo da Fase 2

| Métrica | Valor |
|---------|-------|
| **Total de Tarefas** | 41 |
| **Total de Horas** | ~154 horas |
| **Sprints** | 4 |
| **Duração Estimada** | 6-8 semanas |
| **Concluídas** | 0 |
| **Em Progresso** | 0 |
| **Pendentes** | 41 |

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
| FASE 2 | FASE 1 deve estar 100% completa | ⏳ Aguardando |
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
