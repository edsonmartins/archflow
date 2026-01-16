# archflow

<div align="center">

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java Version](https://img.shields.io/badge/java-%3E%3D17-orange)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-green)](https://spring.io/projects/spring-boot)
[![LangChain4j](https://img.shields.io/badge/LangChain4j-1.10.0-brightgreen)](https://github.com/langchain4j/langchain4j)

**Primeira Plataforma Visual Java-Nativa para IA**

O LangFlow para o mundo Java — Visual AI Builder com Web Component UI

[Features](#-por-que-archflow) • [Quickstart](#-início-rápido) • [Documentação](docs/readme.md) • [Roadmap](docs/roadmap/STATUS-PROJETO.md)

</div>

---

## ✨ Por que archflow?

### O Problema

**78% dos CIOs** citam compliance como barreira para adotar IA.
Empresas Java enfrentam um dilema hoje:

| Opção | Vantagem | Desvantagem |
|-------|----------|--------------|
| **LangFlow / n8n / Dify** | Visual, fácil de usar | ❌ Python/Node.js → não integra com stack Java |
| **Spring AI / LangChain4j** | Java-nativo | ❌ Apenas código → requer especialistas AI |
| **Camunda 8** | Java, enterprise | ❌ BPMN tradicional → não AI-native |

### A Solução

**archflow** é a primeira plataforma visual Java-Nativa para construção de workflows de IA:

<div align="center">

```html
<!-- Funciona em QUALQUER framework -->
<archflow-designer
  workflow-id="customer-support-flow"
  api-base="https://api.archflow.com"
  theme="dark">
</archflow-designer>
```

</div>

### 🎯 Diferenciais Únicos

| Feature | archflow | Python Solutions | Java Frameworks |
|---------|----------|-------------------|-----------------|
| **Backend Java** | ✅ | ❌ | ✅ |
| **Visual Builder** | ✅ | ✅ | ❌ |
| **Web Component UI** | ✅ **ÚNICO** | ❌ | ❌ |
| **Zero Frontend Lock-in** | ✅ | ❌ | ❌ |
| **MCP Native** | ✅ | ⚠️ | ❌ |
| **Enterprise Features** | ✅ | ⚠️ | ✅ |
| **Spring Integration** | ✅ | ❌ | ✅ |

---

## 🚀 Features

### 🎨 Web Component Designer

- **Zero lock-in**: Funciona em React, Vue, Angular, Svelte, vanilla
- **Drag-and-drop**: Crie workflows AI visualmente
- **15+ nodes nativos**: LLM, Tools, Vector Search, Conditions, Parallel, etc.
- **Distribuição via npm**: `npm install @archflow/component`

### 🤖 Java-Nativo AI Engine

- **LangChain4j 1.10.0**: Framework de IA mais moderno do ecossistema Java
- **Spring Boot 3.x**: Integração nativa com o ecossistema Spring
- **15+ LLM Providers**: OpenAI, Anthropic, Azure, AWS, Google, DeepSeek, e mais
- **MCP Protocol**: Interoperabilidade com o ecossistema de AI tools

### 🏢 Enterprise from Day One

- **RBAC**: Controle de acesso baseado em roles
- **Audit Logging**: Rastreabilidade completa de execuções
- **Observabilidade**: Metrics (Prometheus), Tracing (OpenTelemetry), Logging
- **API Keys**: Autenticação programática
- **Suspend/Resume**: Conversações interativas multi-step

---

## 📐 Arquitetura

```
┌─────────────────────────────────────────────────────────────────────┐
│                         archflow-ui (Web Component)                  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │
│  │ <archflow>   │  │  <flow-view> │  │ <chat-panel> │              │
│  │  Designer    │  │  Debugger    │  │  (SSE)       │              │
│  └──────────────┘  └──────────────┘  └──────────────┘              │
└─────────────────────────────────────────────────────────────────────┘
                              ↓ HTTP/WebSocket
┌─────────────────────────────────────────────────────────────────────┐
│                      archflow-server (Spring Boot 3)                │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │
│  │   Flow       │  │    Agent     │  │    Tool      │              │
│  │   Engine     │  │  Executor    │  │  Invoker     │              │
│  └──────────────┘  └──────────────┘  └──────────────┘              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │
│  │    MCP       │  │  Streaming   │  │  Observability│             │
│  │  Protocol    │  │  Protocol    │  │   & Metrics  │              │
│  └──────────────┘  └──────────────┘  └──────────────┘              │
└─────────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────────┐
│                    LangChain4j 1.10.0 + Spring AI                    │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │
│  │  ChatModel   │  │  Embedding   │  │  VectorStore │              │
│  │  (15+ prov.) │  │    Model     │  │   (6+ types) │              │
│  └──────────────┘  └──────────────┘  └──────────────┘              │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 🚀 Início Rápido

### Requisitos

- Java 17+
- Maven 3.9+
- React 19+ (para UI)
- Docker (opcional, para containers)

> **React 19 + Web Components**: archflow usa Web Components que funcionam nativamente com React 19 (lançado Dez/2024). Zero conversão necessária!

### Spring Boot Starter

```xml
<dependency>
    <groupId>org.archflow</groupId>
    <artifactId>archflow-spring-boot-starter</artifactId>
    <version>2.0.0-SNAPSHOT</version>
</dependency>
```

### Docker

```bash
docker run -d \
  -p 8080:8080 \
  -e ARCHFLOW_API_KEY=your-key-here \
  archflow/server:2.0.0
```

### Web Component

```bash
npm install @archflow/component
```

```html
<archflow-designer
  workflow-id="customer-support"
  api-base="http://localhost:8080/api"
  theme="dark">
</archflow-designer>

<script>
  const designer = document.querySelector('archflow-designer');
  designer.addEventListener('workflow-saved', (e) => {
    console.log('Workflow saved:', e.detail);
  });
</script>
```

---

## 📦 Módulos

```
archflow/
├── archflow-core/                    # Core engine
├── archflow-model/                   # Domain models
├── archflow-agent/                   # Agent execution
├── archflow-plugin-api/              # Plugin SPI
├── archflow-langchain4j/             # LangChain4j 1.10.0 integration
│   ├── archflow-langchain4j-core/    # Base interfaces
│   ├── archflow-langchain4j-openai/  # OpenAI + GPT-4.1, o1
│   ├── archflow-langchain4j-anthropic/# Claude 3.5/3.7 Sonnet
│   ├── archflow-langchain4j-mcp/     # MCP Protocol ⭐
│   └── archflow-langchain4j-streaming/ # Streaming support ⭐
├── archflow-server/                  # Spring Boot 3 server ⭐
│   ├── archflow-api/                 # REST/WebSocket APIs
│   ├── archflow-mcp/                 # MCP Server implementation
│   ├── archflow-streaming/           # SSE/WebSocket streaming
│   ├── archflow-observability/       # Metrics, tracing, audit
│   └── archflow-security/            # RBAC, SSO
├── archflow-ui/                      # Web Component distribution ⭐
│   └── archflow-component/           # <archflow-designer>
├── archflow-templates/               # Workflow templates ⭐
└── archflow-enterprise/              # Optional enterprise module ⭐
```

⭐ = Planejado para v2.0

---

## 🗺️ Roadmap

### v2.0.0 (Roadmap Completo)

| Fase | Descrição | Status | Estimativa |
|------|-----------|--------|------------|
| **Fase 1** | Foundation - LangChain4j 1.10.0, Streaming, MCP | 🔴 TODO | 4-6 sem |
| **Fase 2** | Visual Experience - Web Component Designer | 🔴 TODO | 6-8 sem |
| **Fase 3** | Enterprise Capabilities - RBAC, Observability | 🔴 TODO | 4-6 sem |
| **Fase 4** | Ecosystem - Templates, Marketplace | 🔴 TODO | 4-6 sem |
| **Fase 5** | Polish & Launch - Performance, Docs | 🔴 TODO | 2-4 sem |

**Total:** 20-30 semanas até v1.0.0

[Ver roadmap detalhado](docs/roadmap/STATUS-PROJETO.md)

---

## 📚 Documentação

- [Quickstart Guide](docs/development/quickstart.md)
- [Arquitetura](docs/architecture.md)
- [Web Component API](docs/api/web-component.md)
- [REST API Reference](docs/api/rest.md)
- [Guia de Integração](docs/guides/integration.md)
- [Exemplos](docs/examples/README.md)

---

## 🤝 Contribuindo

Contribuições são bem-vindas! Por favor:

1. Leia nosso [Guia de Contribuição](CONTRIBUTING.md)
2. Verifique [Issues abertas](https://github.com/archflow/archflow/issues)
3. Join nosso [Discord](https://discord.gg/archflow)

---

## 💬 Comunidade

- [Discord](https://discord.gg/archflow) - Chat em tempo real
- [GitHub Discussions](https://github.com/archflow/archflow/discussions) - Discussões técnicas
- [Twitter/X](https://twitter.com/archflow_dev) - Novidades e atualizações

---

## 📄 Licença

[Apache License 2.0](LICENSE)

---

## 🙏 Agradecimentos

- [LangChain4j](https://github.com/langchain4j/langchain4j) - Framework de IA para Java
- [Spring AI](https://github.com/spring-projects/spring-ai) - Integração Spring com AI
- [Anthropic](https://www.anthropic.com) - Claude models
- [OpenAI](https://openai.com) - GPT models

---

<div align="center">

**⭐️ Se você acredita que o mundo Java precisa de um visual AI builder próprio, dê uma estrela! ⭐️**

[Comece Agora](docs/development/quickstart.md) • [Documentação](docs/readme.md) • [Discord](https://discord.gg/archflow)

Made with ❤️ by the archflow community

</div>
