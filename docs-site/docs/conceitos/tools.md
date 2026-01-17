---
title: Tools
sidebar_position: 4
slug: tools
---

# Tools (Ferramentas)

## Conceito

**Tools** são funções que agentes AI podem chamar para realizar ações no mundo real - buscar dados, executar comandos, chamar APIs, etc.

## Tool Interface

```java
public interface Tool {
    String getName();
    String getDescription();
    Map<String, ParameterSchema> getParameters();
    ToolResult execute(ToolInput input);
}
```

## Criando Tools

### Tool Simples

```java
public class CalculatorTool extends Tool {

    @Override
    public String getName() {
        return "calculator";
    }

    @Override
    public String getDescription() {
        return "Executa operações matemáticas básicas";
    }

    @Override
    public Map<String, ParameterSchema> getParameters() {
        return Map.of(
            "operation", ParameterSchema.string("Operação: add, subtract, multiply, divide")
                .required()
                .build(),
            "a", ParameterSchema.number("Primeiro número").required().build(),
            "b", ParameterSchema.number("Segundo número").required().build()
        );
    }

    @Override
    public ToolResult execute(ToolInput input) {
        String op = input.getString("operation");
        double a = input.getDouble("a");
        double b = input.getDouble("b");

        return switch (op) {
            case "add" -> ToolResult.success(Map.of("result", a + b));
            case "subtract" -> ToolResult.success(Map.of("result", a - b));
            case "multiply" -> ToolResult.success(Map.of("result", a * b));
            case "divide" -> ToolResult.success(Map.of("result", a / b));
            default -> ToolResult.error("Operação desconhecida: " + op);
        };
    }
}
```

### Tool com Injeção de Dependências

```java
@Component
public class DatabaseQueryTool extends Tool {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseQueryTool(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public ToolResult execute(ToolInput input) {
        String query = input.getString("query");

        // Validação de segurança
        if (!isSafeQuery(query)) {
            return ToolResult.error("Query não permitida");
        }

        List<Map<String, Object>> results = jdbcTemplate.queryForList(query);
        return ToolResult.success(Map.of("rows", results));
    }
}
```

### Tool HTTP/API

```java
public class ApiCallTool extends Tool {

    private final RestTemplate restTemplate;

    @Override
    public ToolResult execute(ToolInput input) {
        String url = input.getString("url");
        String method = input.getString("method", "GET");

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.valueOf(method),
                null,
                String.class
            );

            return ToolResult.success(Map.of(
                "status", response.getStatusCodeValue(),
                "body", response.getBody()
            ));
        } catch (Exception e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
```

## Tool Interceptors

Interceptores permitem adicionar comportamento antes/depois da execução:

```java
@Component
public class LoggingInterceptor implements ToolInterceptor {

    @Override
    public void beforeExecute(ToolContext context) {
        log.info("Executando tool: {}", context.getTool().getName());
    }

    @Override
    public void afterExecute(ToolContext context, ToolResult result) {
        log.info("Tool {} executou em {}ms",
            context.getTool().getName(),
            context.getDuration());
    }
}

@Component
public class CachingInterceptor implements ToolInterceptor {

    private final CacheManager cacheManager;

    @Override
    public void beforeExecute(ToolContext context) {
        String cacheKey = buildCacheKey(context);

        ToolResult cached = cacheManager.get(cacheKey);
        if (cached != null) {
            context.setCachedResult(cached);
        }
    }

    @Override
    public void afterExecute(ToolContext context, ToolResult result) {
        if (result.isSuccess() && context.isCacheable()) {
            cacheManager.put(buildCacheKey(context), result);
        }
    }
}
```

## Tool Registry

 Registre tools para descoberta:

```java
@Component
public class ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    public void register(Tool tool) {
        tools.put(tool.getName(), tool);
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    public List<Tool> getAll() {
        return List.copyOf(tools.values());
    }
}
```

## Built-in Tools

| Tool | Descrição |
|------|-----------|
| `CalculatorTool` | Operações matemáticas |
| `DateTimeTool` | Manipulação de datas |
| `FileTool` | Operações de arquivo |
| `HttpTool` | Chamadas HTTP |
| `SqlTool` | Queries SQL |
| `JsonTool` | Manipulação de JSON |
| `ValidationTool` | Validação de dados |

## Workflow-as-Tool

Workflows podem ser invocados como tools:

```java
public class WorkflowTool extends Tool {

    private final FlowEngine flowEngine;
    private final String workflowId;

    @Override
    public ToolResult execute(ToolInput input) {
        return flowEngine.execute(workflowId, input.getData())
            .toToolResult();
    }
}

// Uso
agent.addTool(new WorkflowTool(flowEngine, "customer-lookup"));
```
