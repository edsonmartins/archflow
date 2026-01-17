---
sidebar_position: 1
title: Introdução
slug: intro
---

# Bem-vindo ao archflow

**archflow** é a primeira plataforma visual Java-Nativa para construção de workflows de Inteligência Artificial.

## O que é archflow?

archflow é um framework open source que permite criar, gerenciar e executar workflows de IA usando uma interface visual, mantendo todo o código backend em Java.

### Por que archflow?

- **100% Java**: Integração nativa com Spring Boot e ecossistema Java
- **Visual Builder**: Crie workflows arrastando e soltando nós
- **Web Component UI**: Funciona com React, Vue, Angular, ou vanilla JavaScript
- **MCP Native**: Integração com Model Context Protocol para interoperabilidade
- **Enterprise-Ready**: RBAC, observabilidade, tracing, e métricas
- **Templates Prontos**: Use templates para casos comuns como suporte ao cliente

## Principais Features

### 🎨 Visual Designer

Crie workflows visualmente usando nosso componente Web:

```html
<archflow-designer
  workflow-id="meu-workflow"
  api-base="http://localhost:8080/api"
  theme="dark">
</archflow-designer>
```

### 🤖 AI Engine

- **LangChain4j 1.10.0**: Framework de IA mais moderno do ecossistema Java
- **15+ LLM Providers**: OpenAI, Anthropic, Azure, AWS, Google, DeepSeek, e mais
- **RAG Built-in**: Retrieval-Augmented Generation com vector stores
- **Multi-Agent**: Coordenação de múltiplos agentes AI

### 🏢 Enterprise

- **RBAC**: Controle de acesso baseado em roles
- **Observabilidade**: Metrics (Prometheus), Tracing (OpenTelemetry)
- **Audit Logging**: Rastreabilidade completa
- **Suspend/Resume**: Conversações interativas multi-step

## Próximos Passos

- [Instalação](/docs/instalacao) - Configure o archflow em seu projeto
- [Primeiro Workflow](/docs/guias/primeiro-workflow) - Crie seu primeiro workflow AI
