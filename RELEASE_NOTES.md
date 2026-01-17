# Release Notes 1.0.0

We're excited to announce archflow 1.0.0 - the first Java-native visual AI builder platform!

## 🎉 What is archflow?

archflow is the **first Java-native visual AI builder** with a Web Component UI that works with any frontend framework. Build, manage, and execute AI workflows using a visual designer while keeping all your backend code in Java.

## 🚀 Key Features

### Visual Workflow Designer
- Drag-and-drop workflow creation
- Real-time validation
- Web Component that works with React, Vue, Angular, or vanilla JS
- Dark/Light theme support

### Enterprise-Ready from Day One
- RBAC (Role-Based Access Control)
- Observability with Prometheus metrics and OpenTelemetry tracing
- Audit logging for complete traceability
- Spring Boot 3 native integration

### AI Engine
- LangChain4j 1.10.0 integration
- 15+ LLM providers (OpenAI, Anthropic, Azure, AWS, Google, DeepSeek, and more)
- RAG (Retrieval-Augmented Generation) built-in
- Multi-agent coordination

### MCP (Model Context Protocol)
- Native MCP server and client
- Interoperability with other AI tools
- Tool discovery and registry

### Performance
- Caffeine-based caching
- Connection pooling with health checking
- Parallel execution with virtual threads (Java 21+)

## 📦 Modules

| Module | Description |
|--------|-------------|
| `archflow-core` | Core workflow execution engine |
| `archflow-model` | Domain models |
| `archflow-agent` | AI agent execution |
| `archflow-langchain4j` | LangChain4j integration |
| `archflow-spring-boot-starter` | Spring Boot auto-configuration |
| `archflow-performance` | Performance optimizations |
| `archflow-observability` | Metrics and tracing |
| `archflow-conversation` | Suspend/resume conversations |
| `archflow-templates` | Workflow templates |
| `archflow-workflow-tool` | Workflow-as-Tool pattern |

## 🚀 Getting Started

### Maven Dependency

```xml
<dependency>
    <groupId>br.com.archflow</groupId>
    <artifactId>archflow-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Configuration

```yaml
archflow:
  llm:
    provider: openai
    api-key: ${OPENAI_API_KEY}
    model: gpt-4o
```

### Web Component

```html
<archflow-designer
  workflow-id="my-workflow"
  api-base="http://localhost:8080/api"
  theme="dark">
</archflow-designer>
```

## 📚 Documentation

Complete documentation is available at [https://archflow.dev](https://archflow.dev)

## 🤝 Contributing

We welcome contributions! Please see our [Contributing Guide](CONTRIBUTING.md) for details.

## 📄 License

Apache License 2.0 - see [LICENSE](LICENSE) for details.

## 🙏 Acknowledgments

- LangChain4j team for the excellent AI framework
- Spring Boot team for the amazing application framework
- All our contributors and early adopters

---

**archflow** - "LangFlow for Java" - Visual AI Builder for Enterprise
