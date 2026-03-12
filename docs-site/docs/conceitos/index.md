---
title: "Visao Geral dos Conceitos"
sidebar_position: 0
slug: /conceitos/
---

# Conceitos do archflow

Visao geral dos conceitos fundamentais do archflow e como eles se relacionam.

## Mapa Conceitual

```
┌─────────────────────────────────────────────────────────────────────┐
│                          archflow                                    │
│                                                                      │
│  ┌───────────────┐    executa     ┌───────────────┐                 │
│  │   Workflow     │ ─────────────→│   Flow Engine  │                │
│  │  (Flow + Steps │               │  (Orquestrador)│                │
│  │  + Connections)│               └───────┬────────┘                │
│  └───────────────┘                        │                         │
│         │                                 │ delega                  │
│         │ contem                           ▼                         │
│         ▼                        ┌───────────────┐                  │
│  ┌───────────────┐               │  Step Executor │                 │
│  │    Steps       │               │  (Por step)    │                 │
│  │  ACTION        │               └───────┬────────┘                │
│  │  DECISION      │                       │                         │
│  │  PARALLEL      │                       │ invoca                  │
│  │  LOOP          │                       ▼                         │
│  │  TOOL_CALL     │              ┌───────────────┐                  │
│  └───────────────┘               │  AI Components │                 │
│                                  │  ┌───────────┐ │                 │
│                                  │  │  Agents   │ │                 │
│                                  │  │Assistants │ │                 │
│                                  │  │  Tools    │ │                 │
│                                  │  └───────────┘ │                 │
│                                  └───────┬────────┘                 │
│                                          │                          │
│                                          │ usa                      │
│                                          ▼                          │
│                                 ┌───────────────┐                   │
│                                 │  LangChain4j   │                  │
│                                 │  (LLM, Memory, │                  │
│                                 │   VectorStore)  │                  │
│                                 └────────────────┘                  │
└─────────────────────────────────────────────────────────────────────┘
```

## Conceitos Fundamentais

### Workflow

Um **Workflow** e a unidade principal de automacao no archflow. Ele define um fluxo de trabalho composto por steps (nos) conectados por connections (arestas).

**Componentes de um workflow:**
- **Metadata** -- Nome, versao, descricao, tags
- **Steps** -- Nos que executam operacoes
- **Connections** -- Arestas que definem a ordem de execucao
- **Configuration** -- Timeout, retry policy, paralelismo

Leia mais em [Workflows](./workflows).

### Agentes AI

Um **Agente** e uma entidade autonoma que usa um modelo de linguagem (LLM) para raciocinar, tomar decisoes e executar acoes atraves de ferramentas (tools). Diferente de assistentes, agentes podem planejar e executar multiplos passos.

**Caracteristicas:**
- Raciocinio com LLM
- Uso autonomo de tools
- Planejamento de acoes
- Memoria de conversacao

Leia mais em [Agentes](./agentes).

### Tools (Ferramentas)

**Tools** sao funcoes que agentes AI podem invocar para realizar acoes concretas -- buscar dados, calcular, chamar APIs, etc. Toda tool tem parametros definidos e validacao.

**Tipos:**
- Tools built-in (TextTransform, WordCount)
- Tools customizadas (via plugin)
- Tools de integracao (web search, database query)

Leia mais em [Tools](./tools).

### Arquitetura

O archflow segue uma arquitetura modular com camadas bem definidas:

| Camada | Modulos | Responsabilidade |
|--------|---------|------------------|
| **Frontend** | archflow-ui | Web Component, visual designer |
| **API** | archflow-api, archflow-security | REST endpoints, autenticacao |
| **Core** | archflow-core, archflow-model | Flow Engine, modelos de dominio |
| **Agent** | archflow-agent | Orquestracao de agentes e plugins |
| **Plugins** | archflow-plugin-* | Componentes extensiveis (tools, agents, assistants) |
| **Integracao** | archflow-langchain4j-* | Adapters para LLMs, memoria, vector stores |
| **Infra** | archflow-performance, archflow-observability | Cache, metricas, tracing |

Leia mais em [Arquitetura](./arquitetura).

## Fluxo de Execucao

Quando um workflow e executado, o seguinte fluxo ocorre:

```
1. API recebe POST /api/workflows/{id}/execute
                    │
2. Flow Engine carrega o workflow (steps + connections)
                    │
3. Para cada step (seguindo connections):
   ├─ ACTION     → Step Executor invoca o componente AI
   ├─ DECISION   → Avalia condicao, escolhe proximo step
   ├─ PARALLEL   → Executa branches em paralelo
   ├─ LOOP       → Repete ate condicao de saida
   └─ TOOL_CALL  → Invoca ferramenta diretamente
                    │
4. Resultado de cada step alimenta o proximo (via ExecutionContext)
                    │
5. Execucao completa → status COMPLETED (ou FAILED com erro)
```

## Plugin System

O archflow usa um sistema de plugins dinamico:

```
┌─────────────────┐     SPI Discovery      ┌──────────────┐
│  Plugin JAR      │ ────────────────────→  │ Plugin Loader │
│  - Tool          │                        │ (Classloader  │
│  - Assistant     │                        │  isolado)     │
│  - Agent         │                        └──────┬───────┘
│  - META-INF/     │                               │
│    services/     │                               ▼
└─────────────────┘                        ┌──────────────┐
                                           │ Plugin Catalog│
                                           │ (Registry)    │
                                           └──────────────┘
```

Plugins sao:
- **Descobertos** via SPI (Service Provider Interface)
- **Isolados** em classloaders separados
- **Versionados** com semver
- **Catalogados** para busca por capabilities e tags

## LangChain4j Integration

O archflow integra com LangChain4j usando um padrao de adapters inspirado no Apache Camel:

| Adapter | Descricao |
|---------|-----------|
| `archflow-langchain4j-openai` | OpenAI (GPT-4, GPT-3.5) |
| `archflow-langchain4j-anthropic` | Anthropic (Claude 3) |
| `archflow-langchain4j-memory-redis` | Memoria de conversacao via Redis |
| `archflow-langchain4j-memory-jdbc` | Memoria de conversacao via banco |
| `archflow-langchain4j-vectorstore-pgvector` | Vector store com pgvector |
| `archflow-langchain4j-vectorstore-pinecone` | Vector store com Pinecone |
| `archflow-langchain4j-vectorstore-redis` | Vector store com Redis |

Adapters sao descobertos via SPI em runtime -- basta adicionar o JAR ao classpath.

## Proximos passos

- [Arquitetura](./arquitetura) -- Detalhes da arquitetura do sistema
- [Workflows](./workflows) -- Como workflows funcionam
- [Agentes](./agentes) -- Como agentes AI funcionam
- [Tools](./tools) -- Como ferramentas funcionam
- [Quickstart](../guias/quickstart-dev) -- Comecar a desenvolver
