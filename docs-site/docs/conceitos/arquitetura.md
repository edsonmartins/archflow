---
title: Arquitetura
sidebar_position: 1
slug: arquitetura
---

# Arquitetura

## Visão Geral

```
┌─────────────────────────────────────────────────────────────┐
│                    Web Component UI                         │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ <archflow>   │  │  <flow-view> │  │ <chat-panel> │      │
│  │  Designer    │  │  Debugger    │  │    (SSE)     │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
                              ↓ HTTP/WebSocket
┌─────────────────────────────────────────────────────────────┐
│                     Spring Boot Server                        │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │   Flow       │  │    Agent     │  │    Tool      │      │
│  │   Engine     │  │  Executor    │  │  Invoker     │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │    MCP       │  │  Streaming   │  │    Cache     │      │
│  │  Protocol    │  │  Protocol    │  │              │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                    LangChain4j 1.10.0                         │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │  ChatModel   │  │  Embedding   │  │ VectorStore  │      │
│  │  (15+ prov.) │  │    Model     │  │   (6+ types) │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
```

## Módulos

| Módulo | Descrição |
|--------|-----------|
| `archflow-core` | Motor de execução de workflows |
| `archflow-model` | Modelos de domínio (Workflow, Flow, etc.) |
| `archflow-agent` | Execução de agentes AI |
| `archflow-langchain4j` | Integração com LangChain4j |
| `archflow-templates` | Templates de workflows prontos |
| `archflow-conversation` | Suspensão/retomada de conversações |
| `archflow-marketplace` | Marketplace de extensões |
| `archflow-workflow-tool` | Workflow-as-Tool pattern |
| `archflow-performance` | Otimizações de performance |

## Camadas

### 1. UI Layer
- Web Component framework-agnostic
- Visual designer
- Chat panel com streaming

### 2. API Layer
- REST endpoints
- WebSocket/SSE streaming
- Event protocol

### 3. Core Layer
- Flow Engine
- Tool Executor
- Agent Supervisor

### 4. AI Layer
- LangChain4j integration
- LLM providers
- Vector stores
