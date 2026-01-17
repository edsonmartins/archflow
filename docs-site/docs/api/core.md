---
title: Core API
sidebar_position: 1
slug: api-core
---

# Core API

## FlowEngine

Motor de execução de workflows.

### Métodos Principais

```java
public interface FlowEngine {

    // Executa um workflow
    ExecutionResult execute(String workflowId, Map<String, Object> input);

    // Executa de forma assíncrona
    CompletableFuture<ExecutionResult> executeAsync(
        String workflowId,
        Map<String, Object> input
    );

    // Registra um workflow
    void register(Workflow workflow);

    // Remove um workflow
    void unregister(String workflowId);

    // Lista workflows registrados
    List<Workflow> listWorkflows();
}
```

### Uso

```java
FlowEngine engine = new DefaultFlowEngine();

// Execução síncrona
ExecutionResult result = engine.execute("my-workflow", Map.of(
    "input", "valor"
));

// Execução assíncrona
CompletableFuture<ExecutionResult> future = engine.executeAsync(
    "my-workflow",
    Map.of("input", "valor")
);

future.thenAccept(result -> {
    System.out.println("Resultado: " + result.getOutput());
});
```

## Workflow

Representa um workflow definido.

### Builder

```java
Workflow workflow = Workflow.builder()
    .id("my-workflow")
    .name("Meu Workflow")
    .description("Descrição do workflow")
    .nodes(List.of(
        // nós
    ))
    .edges(List.of(
        // conexões
    ))
    .variables(Map.of(
        "var1", "valor"
    ))
    .build();
```

### Propriedades

| Propriedade | Tipo | Descrição |
|-------------|------|-----------|
| `id` | String | Identificador único |
| `name` | String | Nome do workflow |
| `description` | String | Descrição |
| `nodes` | List\<Node\> | Lista de nós |
| `edges` | List\<Edge\> | Lista de conexões |
| `variables` | Map | Variáveis globais |

## Node

Um nó dentro do workflow.

### Tipos de Nós

```java
// Nó de entrada
InputNode.builder()
    .id("input")
    .field("name", FieldType.TEXT)
    .field("age", FieldType.NUMBER)
    .required("name")
    .build();

// Nó LLM
LLMNode.builder()
    .id("llm")
    .model("gpt-4o")
    .prompt("Process: {input}")
    .temperature(0.7)
    .maxTokens(1000)
    .build();

// Nó de ferramenta
ToolNode.builder()
    .id("tool")
    .toolName("my-tool")
    .timeout(Duration.ofSeconds(30))
    .build();

// Nó de condição
ConditionNode.builder()
    .id("condition")
    .expression("${value > 10}")
    .build();

// Nó de saída
OutputNode.builder()
    .id("output")
    .template("Resultado: ${llm.output}")
    .build();
```

## Edge

Conexão entre nós.

### Tipos

```java
// Conexão simples
Edge.from("node1").to("node2");

// Conexão condicional
Edge.from("decision")
    .to("branch-a")
    .when("${condition == 'A'}");

Edge.from("decision")
    .to("branch-b")
    .when("${condition == 'B'}");

// Conexão com rótulo
Edge.from("node1")
    .to("node2")
    .label("success");

Edge.from("node1")
    .to("node3")
    .label("error");
```

## ExecutionResult

Resultado da execução de um workflow.

### Métodos

```java
public class ExecutionResult {

    // Status da execução
    public ExecutionStatus getStatus(); // SUCCESS, ERROR, TIMEOUT

    // Saída do workflow
    public Object getOutput();

    // Saída formatada
    public String getOutputAsString();

    // Variáveis de contexto
    public Map<String, Object> getContext();

    // Duração
    public Duration getDuration();

    // Erro (se houver)
    public Throwable getError();

    // Execução de nós individuais
    public List<NodeExecution> getNodeExecutions();
}
```

## Context

Variáveis de contexto durante execução.

### Acesso

```java
// Dentro de expressões
"${nodeId.output}"        // Saída de um nó específico
"${workflow.id}"          // ID do workflow
"${execution.id}"         // ID da execução
"${input.varName}"        // Variável de entrada
"${fn:uppercase(text)}"   // Função utilitária
```

### Funções Disponíveis

| Função | Descrição | Exemplo |
|--------|-----------|---------|
| `uppercase` | Converte para maiúsculas | `${fn:uppercase(text)}` |
| `lowercase` | Converte para minúsculas | `${fn:lowercase(text)}` |
| `timestamp` | Timestamp atual | `${fn:timestamp()}` |
| `uuid` | UUID aleatório | `${fn:uuid()}` |
| `jsonPath` | Extrai valor JSON | `${fn:jsonPath(data, '$.field')}` |
| `format` | Formata string | `${fn:format('Olá %s', name)}` |
