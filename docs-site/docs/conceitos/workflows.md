---
title: Workflows
sidebar_position: 2
slug: workflows
---

# Workflows

## Conceito

Um **Workflow** no archflow é uma composição de nós que executam tarefas em sequência ou paralelo, permitindo criar pipelines de IA complexos.

## Estrutura

```java
Workflow workflow = Workflow.builder()
    .id("customer-support")
    .name("Fluxo de Suporte ao Cliente")
    .description("Workflow para atendimento automatizado")
    .nodes(List.of(
        // Nós do workflow
    ))
    .edges(List.of(
        // Conexões entre nós
    ))
    .build();
```

## Tipos de Nós

| Tipo | Descrição |
|------|-----------|
| `InputNode` | Entrada de dados do workflow |
| `LLMNode` | Chamada a modelo de linguagem |
| `ToolNode` | Execução de ferramenta |
| `AgentNode` | Execução de agente AI |
| `OutputNode` | Saída de dados do workflow |
| `ConditionNode` | Decisão condicional |
| `ParallelNode` | Execução paralela de branches |
| `LoopNode` | Iteração sobre coleção |

## Exemplo Completo

```java
// Cria um workflow de suporte
Workflow workflow = Workflow.builder()
    .id("support-flow")
    .nodes(List.of(
        InputNode.builder()
            .id("input")
            .schema(FormSchema.builder()
                .field("message", FieldType.TEXT)
                .build())
            .build(),

        LLMNode.builder()
            .id("classifier")
            .model("gpt-4o")
            .prompt("Classifique a solicitação: {input.message}")
            .build(),

        ConditionNode.builder()
            .id("route")
            .expression("${classifier.category == 'technical'}")
            .build(),

        AgentNode.builder()
            .id("tech-agent")
            .agentId("technical-support")
            .build(),

        AgentNode.builder()
            .id("general-agent")
            .agentId("general-support")
            .build(),

        OutputNode.builder()
            .id("output")
            .template("Resposta: ${lastAgent.output}")
            .build()
    ))
    .edges(List.of(
        Edge.from("input").to("classifier"),
        Edge.from("classifier").to("route"),
        Edge.from("route").to("tech-agent").when("true"),
        Edge.from("route").to("general-agent").when("false"),
        Edge.from("tech-agent").to("output"),
        Edge.from("general-agent").to("output")
    ))
    .build();
```

## Variáveis de Contexto

Os workflows podem passar dados entre nós usando variáveis de contexto:

```java
// Referenciar saída de outro nó
"${nodeId.output}"

// Acessar metadados
"${workflow.id}"
"${execution.id}"

// Funções utilitárias
"${fn:uppercase(text)}"
"${fn:timestamp()}"
```

## Execução

```java
FlowEngine engine = new FlowEngine();

ExecutionResult result = engine.execute(workflow, Map.of(
    "input", Map.of("message", "Preciso de ajuda técnica")
));

String output = result.getOutput();
```
