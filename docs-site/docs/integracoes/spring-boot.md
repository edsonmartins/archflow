---
title: Spring Boot
sidebar_position: 1
slug: spring-boot
---

# Integração com Spring Boot

## Auto-Configuration

O archflow se integra automaticamente com Spring Boot 3.x.

## Dependência

```xml
<dependency>
    <groupId>br.com.archflow</groupId>
    <artifactId>archflow-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Configuração

### application.yml

```yaml
archflow:
  # API Configuration
  api:
    enabled: true
    base-path: /api
    cors:
      enabled: true
      allowed-origins: "*"

  # LLM Configuration
  llm:
    provider: openai
    api-key: ${OPENAI_API_KEY}
    model: gpt-4o
    temperature: 0.7
    max-tokens: 2000
    timeout: 30s

  # Embedding Configuration
  embedding:
    provider: openai
    model: text-embedding-3-small
    dimension: 1536

  # Vector Store
  vector-store:
    type: weaviate
    url: http://localhost:8080
    api-key: ${WEAVIATE_API_KEY}

  # Security
  security:
    enabled: true
    api-key: ${ARCHFLOW_API_KEY}

  # Observability
  observability:
    metrics:
      enabled: true
    tracing:
      enabled: true
```

## Beans Disponíveis

### Core Beans

```java
@Service
public class MyService {

    private final FlowEngine flowEngine;
    private final AgentExecutor agentExecutor;
    private final ToolRegistry toolRegistry;

    public MyService(
            FlowEngine flowEngine,
            AgentExecutor agentExecutor,
            ToolRegistry toolRegistry) {
        this.flowEngine = flowEngine;
        this.agentExecutor = agentExecutor;
        this.toolRegistry = toolRegistry;
    }
}
```

### LLM Beans

```java
@Service
public class ChatService {

    private final ChatLanguageModel chatModel;
    private final StreamingChatLanguageModel streamingModel;
    private final EmbeddingModel embeddingModel;

    // Injetados automaticamente pela configuração
}
```

### Vector Store Beans

```java
@Service
public class RagService {

    private final VectorStore vectorStore;
    private final EmbeddingStore embeddingStore;

    // Injetados automaticamente
}
```

## Criando Workflows

### Declarativo com @Workflow

```java
@Component
@Workflow(id = "customer-support", name = "Suporte ao Cliente")
public class CustomerSupportWorkflow {

    @Node(id = "input", type = "input")
    public InputNode input() {
        return InputNode.builder()
            .field("message", FieldType.TEXT)
            .build();
    }

    @Node(id = "classify", type = "llm")
    public LLMNode classify() {
        return LLMNode.builder()
            .prompt("Classifique: {input.message}")
            .build();
    }

    @Edge(from = "input", to = "classify")
    public void inputToClassify() {}

    @Edge(from = "classify", to = "resolve")
    public void classifyToResolve(@Condition("${category == 'support'}")) {}
}
```

### Programático

```java
@Configuration
public class WorkflowConfig {

    @Bean
    public Workflow supportWorkflow() {
        return Workflow.builder()
            .id("support")
            .nodes(List.of(/* ... */))
            .edges(List.of(/* ... */))
            .build();
    }
}
```

## Criando Agents

### @Agent Annotation

```java
@Component
@Agent(
    id = "support-agent",
    name = "Agente de Suporte",
    systemPrompt = "Você é um agente de suporte útil."
)
public class SupportAgent {

    @Tool
    public String lookupCustomer(String email) {
        // Busca cliente
        return customerService.findByEmail(email);
    }

    @Tool
    public String getOrderStatus(String orderId) {
        // Busca pedido
        return orderService.getStatus(orderId);
    }
}
```

### Programático

```java
@Configuration
public class AgentConfig {

    @Bean
    public Agent supportAgent(ChatLanguageModel llm) {
        return Agent.builder()
            .id("support")
            .llm(llm)
            .tools(List.of(/* ... */))
            .build();
    }
}
```

## Criando Tools

### @Tool Annotation

```java
@Component
public class CustomerTools {

    @Tool(name = "customer_lookup", description = "Busca cliente")
    public String lookupCustomer(
            @Param("email") String email,
            @Param("include_orders") boolean includeOrders) {
        // Implementação
        return customer.toString();
    }
}
```

### Implementando Interface

```java
@Component
public class WeatherTool implements Tool {

    @Override
    public String getName() {
        return "weather";
    }

    @Override
    public ToolResult execute(ToolInput input) {
        // Implementação
    }
}
```

## REST Controllers

### API Incluída

O starter inclui controllers REST prontos:

```
POST   /api/workflows/{id}/execute
GET    /api/workflows
GET    /api/workflows/{id}
POST   /api/agents/{id}/chat
GET    /api/agents
GET    /api/tools
```

### Custom Controllers

```java
@RestController
@RequestMapping("/my-api")
public class MyController {

    private final FlowEngine flowEngine;

    @PostMapping("/process")
    public ResponseEntity<Map<String, String>> process(
            @RequestBody Map<String, String> request) {

        String result = flowEngine.execute("my-workflow", request);
        return ResponseEntity.ok(Map.of("result", result));
    }
}
```

## Properties

### Archflow Properties

| Property | Tipo | Padrão | Descrição |
|----------|------|--------|-----------|
| `archflow.api.enabled` | boolean | true | Habilita API REST |
| `archflow.api.base-path` | String | /api | Path base da API |
| `archflow.llm.provider` | String | - | Provider LLM |
| `archflow.llm.model` | String | gpt-4o | Modelo padrão |
| `archflow.llm.temperature` | double | 0.7 | Temperatura padrão |
| `archflow.security.enabled` | boolean | false | Habilita segurança |
| `archflow.observability.metrics.enabled` | boolean | true | Habilita métricas |

## Actuator Endpoints

```bash
# Métricas
curl http://localhost:8080/actuator/metrics/archflow.executions

# Health check
curl http://localhost:8080/actuator/health

# Info
curl http://localhost:8080/actuator/info
```

## Testes

### @ArchflowTest

```java
@ArchflowTest
class MyWorkflowTest {

    @Autowired
    private FlowEngine flowEngine;

    @Test
    void shouldExecuteWorkflow() {
        ExecutionResult result = flowEngine.execute(
            "test-workflow",
            Map.of("input", "test")
        );

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
    }
}
```
