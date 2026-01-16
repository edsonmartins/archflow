# Plano de Execução – archflow 2.0

> **Instrução:** Sempre que uma tarefa avançar de status, atualize esta tabela com a nova situação e registre a data no campo "Última atualização". Os status sugeridos são `TODO`, `IN_PROGRESS`, `BLOCKED` e `DONE`.

---

## Legend

| Status | Descrição |
|--------|-----------|
| `TODO` | Tarefa ainda não iniciada |
| `IN_PROGRESS` | Tarefa em execução |
| `BLOCKED` | Tarefa impedida por dependência externa |
| `DONE` | Tarefa concluída e validada |

---

## Prioridades

| Prioridade | Descrição |
|------------|-----------|
| 🔴 ALTA | Crítica para o MVP |
| 🟡 MÉDIA | Importante mas não bloqueia |
| 🟢 BAIXA | Nice to have |

---

## 📋 CONTEXTO DO PROJETO

**archflow 2.0** é a primeira plataforma visual Java-Nativa para construção de workflows de IA.

**Posicionamento Único:**
- "LangFlow para o mundo Java"
- Web Component UI (zero frontend lock-in)
- MCP (Model Context Protocol) nativo
- Enterprise features from day one

**Stack Tecnológico:**
- Backend: Java 17+, Spring Boot 3.x, LangChain4j 1.10.0
- Frontend: React 19 (uso) + Web Component (distribuição)
- AI: LangChain4j 1.10.0, Spring AI 1.1+
- Protocolos: MCP v1.0, SSE, WebSocket
- Enterprise: Spring Security, Keycloak, OpenTelemetry

**Objetivo:** Primeiro lançamento (v1.0.0) em 20-30 semanas

---

## 📊 STATUS GERAL DO PROJETO

**Última atualização:** 2025-01-15

### Resumo por Fase

| Fase | Descrição | Progresso | Status | Tarefas | Horas |
|------|-----------|-----------|--------|---------|-------|
| **FASE 1** | Foundation | 0% | 🔴 TODO | 0/37 | ~125h |
| **FASE 2** | Visual Experience | 0% | 🔴 TODO | 0/41 | ~154h |
| **FASE 3** | Enterprise Capabilities | 0% | 🔴 TODO | 0/46 | ~153h |
| **FASE 4** | Ecosystem | 0% | 🔴 TODO | 0/49 | ~183h |
| **FASE 5** | Polish & Launch | 0% | 🔴 TODO | 0/55 | ~220h |

**Status Geral:** 🔴 **PROJETO INICIANDO** - Aguardando início da Fase 1

**Progresso Total:** 0% (0/228 tarefas)

**Total Estimado:** ~835 horas (~20-30 semanas)

---

## 📦 Módulos Previstos

```
archflow/
├── archflow-core/                    # Core engine
├── archflow-model/                   # Domain models
├── archflow-agent/                   # Agent execution
├── archflow-plugin-api/              # Plugin SPI
├── archflow-langchain4j/             # LangChain4j 1.10.0 integration
│   ├── archflow-langchain4j-core/
│   ├── archflow-langchain4j-openai/
│   ├── archflow-langchain4j-anthropic/
│   ├── archflow-langchain4j-mcp/
│   ├── archflow-langchain4j-streaming/
│   └── archflow-langchain4j-spring-ai/
├── archflow-server/                  # Spring Boot 3 server
│   ├── archflow-api/
│   ├── archflow-mcp/
│   ├── archflow-streaming/
│   ├── archflow-observability/
│   └── archflow-security/
├── archflow-ui/                      # Web Component distribution
│   ├── archflow-component/
│   ├── archflow-designer/
│   ├── archflow-chat/
│   └── archflow-admin/
├── archflow-templates/               # Workflow templates
└── archflow-enterprise/              # Optional enterprise module
```

---

## 🔗 Links para Documentos de Fases

| Fase | Documento Detalhado | Status |
|------|---------------------|--------|
| [FASE 1: Foundation](./fase-1-tarefas.md) | [Ver documento](./fase-1-tarefas.md) | 🔴 TODO |
| [FASE 2: Visual Experience](./fase-2-tarefas.md) | [Ver documento](./fase-2-tarefas.md) | 🔴 TODO |
| [FASE 3: Enterprise Capabilities](./fase-3-tarefas.md) | [Ver documento](./fase-3-tarefas.md) | 🔴 TODO |
| [FASE 4: Ecosystem](./fase-4-tarefas.md) | [Ver documento](./fase-4-tarefas.md) | 🔴 TODO |
| [FASE 5: Polish & Launch](./fase-5-tarefas.md) | [Ver documento](./fase-5-tarefas.md) | 🔴 TODO |

---

## 📝 Log de Mudanças

### 2025-01-15
- ✅ Criação do documento de status principal (STATUS-PROJETO.md)
- ✅ Criação dos documentos de tarefas por fase (fase-*-tarefas.md)
- 📋 Projeto definido com 228 tarefas distribuídas em 5 fases
- 📊 Total estimado: ~835 horas (20-30 semanas)

---

## 🎯 Próximos Passos Imediatos

1. ✅ Validar React 19 + Web Components (ANÁLISE COMPLETA FEITA)
2. Criar POC do Web Component com React 19
3. Definir spec do Streaming Protocol detalhado
4. Criar manifest de Extension Marketplace
5. Especificar toolCallId system completamente
6. Atualizar archflow-langchain4j para 1.10.0

---

## 🔬 Decisão Arquitetural: React 19 + Web Component

### Análise Completa Realizada

**Data:** 15 de Janeiro de 2026
**Documento:** [docs/analysis/react-to-web-component-analysis.md](../analysis/react-to-web-component-analysis.md)

### Conclusão

✅ **React 19 (Dez/2024) tem suporte NATIVO a Web Components**

| Opção | Viabilidade | Risco | Decisão |
|-------|-------------|-------|---------|
| **React 19 Nativo** | ✅ Alta | 🟢 Baixo | ✅ **ESCOLHIDO** |
| @r2wc/react-to-web-component | ⚠️ Média | 🟠 Médio | ❌ Descartado (baixa manutenção) |
| Preact | ✅ Alta | 🟢 Baixo | ⚠️ Alternativa se necessário |
| Svelte → WC | ✅ Alta | 🟢 Baixo | ❌ Stack diferente |

### Estratégia de Implementação

```
archflow-ui/
├── archflow-component/          # Web Component (TypeScript puro)
│   ├── src/
│   │   ├── ArchflowDesigner.ts  # HTMLElement class
│   │   ├── Canvas.ts
│   │   ├── nodes/
│   │   └── styles/
│   └── package.json             # @archflow/component
│
└── examples/
    └── react/                   # Exemplo React 19
        └── App.tsx              # <archflow-designer> direto
```

### Problemas Conhecidos e Mitigações

| Problema | Mitigação |
|----------|-----------|
| Attributes vs Properties | Implementar ambos no WC |
| Sem Declarative Shadow DOM | Client-side rendering |

### Fontes

- [React v19 Announcement](https://react.dev/blog/2024/12/05/react-19)
- [React 19 and Web Component Examples](https://frontendmasters.com/blog/react-19-and-web-component-examples/)

---

## 📌 Notas Importantes

- **Framework target:** LangChain4j 1.10.0 (atual: 1.0.0-beta1)
- **Breaking changes:** Muitos entre 1.0.0 e 1.10.0
- **Diferencial principal:** Web Component UI
- **MCP é prioridade:** 3 de 6 concorrentes já têm
- **Enterprise from day one:** RBAC, audit, métricas, compliance
