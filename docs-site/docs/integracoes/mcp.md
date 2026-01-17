---
title: MCP (Model Context Protocol)
sidebar_position: 2
slug: mcp
---

# Integração MCP

O archflow suporta nativamente o Model Context Protocol (MCP) para interoperabilidade de ferramentas AI.

## O que é MCP?

MCP é um protocolo aberto que permite que aplicações AI exponham e consumam ferramentas de forma padronizada.

```
┌─────────────────────────────────────────────────────────────┐
│                    MCP Client                                │
│  (Claude Desktop, ChatGPT, ou outro aplicativo)             │
└─────────────────────────────────────────────────────────────┘
                              ↓ MCP Protocol
┌─────────────────────────────────────────────────────────────┐
│                    MCP Server                                │
│  (archflow expõe tools e workflows)                         │
└─────────────────────────────────────────────────────────────┘
```

## Configuração do Servidor MCP

### application.yml

```yaml
archflow:
  mcp:
    enabled: true
    server:
      name: archflow-server
      version: 1.0.0
      transport: stdio  # ou sse
    tools:
      expose-workflows: true
      expose-agents: true
```

### Maven Dependency

```xml
<dependency>
    <groupId>br.com.archflow</groupId>
    <artifactId>archflow-mcp-server</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## MCP Server

### Registro de Tools

```java
@Component
public class MCPServerConfig {

    @Autowired
    private MCPServer mcpServer;

    @Autowired
    private ToolRegistry toolRegistry;

    @PostConstruct
    public void registerTools() {
        // Registra todas as tools do archflow como MCP tools
        for (Tool tool : toolRegistry.getAll()) {
            mcpServer.registerTool(tool);
        }
    }
}
```

### Expondo Workflows

```java
@Component
public class WorkflowMCPBridge {

    @Autowired
    private MCPServer mcpServer;

    @Autowired
    private FlowEngine flowEngine;

    @PostConstruct
    public void exposeWorkflows() {
        // Cada workflow vira uma MCP tool
        for (Workflow workflow : flowEngine.listWorkflows()) {
            MCPTool tool = MCPTool.builder()
                .name(workflow.getId())
                .description(workflow.getDescription())
                .inputSchema(workflow.getInputSchema())
                .handler(input -> {
                    return flowEngine.execute(workflow.getId(), input);
                })
                .build();

            mcpServer.registerTool(tool);
        }
    }
}
```

## MCP Client

O archflow também pode consumir MCP servers externos.

### Conectando a MCP Server

```java
@Configuration
public class MCPClientConfig {

    @Bean
    public MCPClient mcpClient() {
        return MCPClient.builder()
            .transport("stdio")
            .command("node")
            .args(List.of("path/to/server.js"))
            .build();
    }

    @Bean
    public List<MCPTool> externalTools(MCPClient client) {
        // Descobre tools disponíveis
        return client.listTools();
    }
}
```

### Usando Tools MCP Externas

```java
@Service
public class AgentWithMCPTools {

    public Agent createAgent(ChatLanguageModel llm, List<MCPTool> mcpTools) {
        return Agent.builder()
            .id("agent-with-mcp")
            .llm(llm)
            .tools(mcpTools)
            .build();
    }
}
```

## Ferramentas MCP Internas

### Tool Registry

```java
// Listar todas as tools expostas via MCP
GET /mcp/v1/tools

// Response
{
  "tools": [
    {
      "name": "customer_lookup",
      "description": "Busca informações do cliente",
      "inputSchema": {
        "type": "object",
        "properties": {
          "email": { "type": "string" }
        },
        "required": ["email"]
      }
    }
  ]
}
```

### Call Tool

```java
// Chamar tool via MCP
POST /mcp/v1/tools/call

{
  "name": "customer_lookup",
  "arguments": {
    "email": "user@example.com"
  }
}

// Response
{
  "result": {
    "name": "João Silva",
    "tier": "premium"
  }
}
```

## Claude Desktop Integration

### Claude Desktop Config

```json
{
  "mcpServers": {
    "archflow": {
      "command": "java",
      "args": [
        "-jar",
        "/path/to/archflow-mcp-server.jar"
      ],
      "env": {
        "OPENAI_API_KEY": "sk-..."
      }
    }
  }
}
```

### Usando no Claude

Após configurar, você pode usar as tools do archflow diretamente no Claude Desktop:

```
User: Use customer_lookup para buscar o usuário joao@example.com

Claude: [Chama a tool MCP customer_lookup]

Encontrei o usuário João Silva, tier premium...
```

## Recursos MCP

### Recursos

O archflow pode expor recursos (documentos, knowledge bases) via MCP:

```java
@Component
public class MCPResourceRegistry {

    @Autowired
    private MCPServer mcpServer;

    @PostConstruct
    public void registerResources() {
        mcpServer.registerResource(Resource.builder()
            .uri("file:///knowledge/base")
            .name "Knowledge Base"
            .description("Documentação do produto")
            .mimeType("text/plain")
            .build());
    }
}
```

### Prompts

Exponha prompts configuráveis:

```java
@Component
public class MCPPrompts {

    @Autowired
    private MCPServer mcpServer;

    @PostConstruct
    public void registerPrompts() {
        mcpServer.registerPrompt(Prompt.builder()
            .name("summarize")
            .description("Resume um documento")
            .arguments(List.of(
                Argument.builder()
                    .name("document")
                    .description("Documento para resumir")
                    .required(true)
                    .build()
            ))
            .build());
    }
}
```

## Exemplo Completo

```java
@SpringBootApplication
@EnableMCP
public class ArchflowMCPApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArchflowMCPApplication.class, args);
    }

    @Bean
    public MCPServer mcpServer(
            ToolRegistry toolRegistry,
            FlowEngine flowEngine) {

        MCPServer server = MCPServer.builder()
            .name("archflow")
            .version("1.0.0")
            .build();

        // Registra tools nativas
        toolRegistry.getAll().forEach(server::registerTool);

        // Registra workflows como tools
        flowEngine.listWorkflows().forEach(workflow -> {
            MCPTool tool = MCPTool.builder()
                .name("workflow_" + workflow.getId())
                .description(workflow.getDescription())
                .inputSchema(workflow.getInputSchema())
                .handler(input -> flowEngine.execute(workflow.getId(), input))
                .build();
            server.registerTool(tool);
        });

        return server;
    }
}
```
