# FASE 4: Ecosystem - Lista de Tarefas

**Duração Estimada:** 4-6 semanas (4 sprints)
**Objetivo:** Construir ecossistema com templates, marketplace e composição avançada
**Dependência:** FASE 2 deve estar 100% completa

---

## Sprint 13: Workflow Templates

| ID | Tarefa | Estimativa | Prioridade | Status | Última Atualização |
|----|--------|------------|------------|--------|-------------------|
| F4-01 | Criar TemplateMetadata entity | 2h | 🔴 ALTA | TODO | - |
| F4-02 | Criar TemplateParameter entity com tipos | 3h | 🔴 ALTA | TODO | - |
| F4-03 | Definir schema YAML para templates | 3h | 🔴 ALTA | TODO | - |
| F4-04 | Implementar TemplateEngine | 4h | 🔴 ALTA | TODO | - |
| F4-05 | Implementar validação de parâmetros | 3h | 🔴 ALTA | TODO | - |
| F4-06 | Implementar substituição de variáveis | 2h | 🔴 ALTA | TODO | - |
| F4-07 | Criar BuiltinTemplateRegistry | 2h | 🟡 MÉDIA | TODO | - |
| F4-08 | Criar template: Customer Support RAG | 4h | 🟡 MÉDIA | TODO | - |
| F4-09 | Criar template: Document Processor | 4h | 🟡 MÉDIA | TODO | - |
| F4-10 | Criar template: Knowledge Base RAG | 3h | 🟡 MÉDIA | TODO | - |
| F4-11 | Criar template: Agent Supervisor | 4h | 🟡 MÉDIA | TODO | - |
| F4-12 | Implementar preview de template sem instalar | 3h | 🟡 MÉDIA | TODO | - |
| F4-13 | Criar endpoints /api/templates (list, install, preview) | 3h | 🔴 ALTA | TODO | - |

**Subtotal:** 40 horas (~1 semana)

---

## Sprint 14: Suspend/Resume

| ID | Tarefa | Estimativa | Prioridade | Status | Última Atualização |
|----|--------|------------|------------|--------|-------------------|
| F4-14 | Criar Conversation entity com estados | 2h | 🔴 ALTA | TODO | - |
| F4-15 | Criar ConversationMessage entity | 2h | 🔴 ALTA | TODO | - |
| F4-16 | Criar FormData e FormField models | 3h | 🔴 ALTA | TODO | - |
| F4-17 | Implementar ConversationService | 4h | 🔴 ALTA | TODO | - |
| F4-18 | Implementar método suspend() | 3h | 🔴 ALTA | TODO | - |
| F4-19 | Implementar método resume() | 3h | 🔴 ALTA | TODO | - |
| F4-20 | Implementar geração de resumeToken | 2h | 🔴 ALTA | TODO | - |
| F4-21 | Criar SSE events interaction:suspend e interaction:resume | 2h | 🔴 ALTA | TODO | - |
| F4-22 | Implementar renderização de forms no chat | 4h | 🟡 MÉDIA | TODO | - |
| F4-23 | Criar endpoints /api/conversations (suspend, resume) | 3h | 🔴 ALTA | TODO | - |
| F4-24 | Implementar expiração de conversas suspensas | 2h | 🟡 MÉDIA | TODO | - |
| F4-25 | Criar exemplo de tool com file upload suspend | 2h | 🟡 MÉDIA | TODO | - |

**Subtotal:** 32 horas (~1 semana)

---

## Sprint 15: Extension Marketplace

| ID | Tarefa | Estimativa | Prioridade | Status | Última Atualização |
|----|--------|------------|------------|--------|-------------------|
| F4-26 | Definir schema ExtensionManifest (TypeScript) | 3h | 🔴 ALTA | TODO | - |
| F4-27 | Criar Extension entity no banco | 2h | 🔴 ALTA | TODO | - |
| F4-28 | Implementar ExtensionSignatureValidator | 4h | 🔴 ALTA | TODO | - |
| F4-29 | Implementar PermissionValidator | 3h | 🔴 ALTA | TODO | - |
| F4-30 | Criar DependencyResolver | 3h | 🟡 MÉDIA | TODO | - |
| F4-31 | Implementar ExtensionInstaller | 4h | 🔴 ALTA | TODO | - |
| F4-32 | Implementar download de artefatos | 3h | 🟡 MÉDIA | TODO | - |
| F4-33 | Implementar installBackend() | 3h | 🔴 ALTA | TODO | - |
| F4-34 | Implementar installFrontend() | 2h | 🟡 MÉDIA | TODO | - |
| F4-35 | Implementar uninstall() | 2h | 🟡 MÉDIA | TODO | - |
| F4-36 | Criar endpoints /api/extensions (marketplace, install, uninstall) | 4h | 🔴 ALTA | TODO | - |
| F4-37 | Implementar search com filtros | 3h | 🟡 MÉDIA | TODO | - |
| F4-38 | Criar extensão exemplo para validar fluxo | 2h | 🟢 BAIXA | TODO | - |

**Subtotal:** 38 horas (~1 semana)

---

## Sprint 16: Workflow-as-Tool

| ID | Tarefa | Estimativa | Prioridade | Status | Última Atualização |
|----|--------|------------|------------|--------|-------------------|
| F4-39 | Criar WorkflowTool wrapper | 4h | 🔴 ALTA | TODO | - |
| F4-40 | Implementar extração de input schema do workflow | 3h | 🔴 ALTA | TODO | - |
| F4-41 | Criar WorkflowToolRegistry | 3h | 🔴 ALTA | TODO | - |
| F4-42 | Implementar validação de workflow como tool | 2h | 🔴 ALTA | TODO | - |
| F4-43 | Implementar registro automático de workflows exportable | 2h | 🟡 MÉDIA | TODO | - |
| F4-44 | Criar LangChain4jToolAdapter | 4h | 🔴 ALTA | TODO | - |
| F4-45 | Implementar ToolSpecifications para LangChain4j | 3h | 🔴 ALTA | TODO | - |
| F4-46 | Implementar execução com tracking hierárquico | 3h | 🔴 ALTA | TODO | - |
| F4-47 | Criar exemplo: order-processing com sub-workflows | 3h | 🟡 MÉDIA | TODO | - |
| F4-48 | Criar exemplo: customer-support hierarchy | 3h | 🟡 MÉDIA | TODO | - |
| F4-49 | Testar composição de 3+ níveis de profundidade | 3h | 🔴 ALTA | TODO | - |

**Subtotal:** 33 horas (~1 semana)

---

## 📊 Resumo da Fase 4

| Métrica | Valor |
|---------|-------|
| **Total de Tarefas** | 49 |
| **Total de Horas** | ~183 horas |
| **Sprints** | 4 |
| **Duração Estimada** | 4-6 semanas |
| **Concluídas** | 0 |
| **Em Progresso** | 0 |
| **Pendentes** | 49 |

---

## ✅ Critérios de Sucesso da Fase 4

- [ ] 4+ templates built-in instaláveis
- [ ] Sistema de parâmetros configurável
- [ ] Preview de workflow antes da instalação
- [ ] Conversas podem ser suspensas e retomadas
- [ ] Formulários interativos renderizados no chat
- [ ] Marketplace de extensões funcional
- [ ] Extensões podem ser instaladas/removidas
- [ ] Validação de assinatura de extensões
- [ ] Workflows podem ser invocados como tools
- [ ] Hierarquia de execução visível no tracing
- [ ] Templates comunitários podem ser publicados

---

## 🔗 Dependências

| Fase | Dependência | Status |
|------|-------------|--------|
| FASE 4 | FASE 2 deve estar 100% completa | ⏳ Aguardando |
| FASE 5 | FASE 4 deve estar 100% completa | ⏳ Aguardando |

---

## 📝 Notas

- **Templates:** Devem ser versionados
- **Suspend/Resume:** Conversas expiram em 24h por padrão
- **Marketplace:** Extensões oficiais vs comunitárias
- **Workflow-as-Tool:** Habilita composição poderosa
- **Comunidade:** Templates devem ser fáceis de contribuir
