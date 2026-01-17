---
title: Agentes
sidebar_position: 3
slug: agentes
---

# Agentes AI

## Conceito

Um **Agente** no archflow é uma entidade que usa um modelo de linguagem para raciocinar e executar ações através de ferramentas (tools).

## Arquitetura

```
┌─────────────────────────────────────────────────────────┐
│                    Agente AI                             │
│  ┌─────────────────────────────────────────────────┐   │
│  │  Prompt Template + Contexto da Conversação      │   │
│  └─────────────────────────────────────────────────┘   │
│                          ↓                              │
│  ┌─────────────────────────────────────────────────┐   │
│  │  LLM (ChatLanguageModel)                        │   │
│  └─────────────────────────────────────────────────┘   │
│                          ↓                              │
│  ┌─────────────────────────────────────────────────┐   │
│  │  Tool Executor                                  │   │
│  │  - Seleciona tools                              │   │
│  │  - Executa com interceptores                    │   │
│  │  - Processa resultado                           │   │
│  └─────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

## Criando um Agente

```java
Agent agent = Agent.builder()
    .id("customer-support")
    .name("Agente de Suporte")
    .model(ChatLanguageModel.builder()
        .provider("openai")
        .modelId("gpt-4o")
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .build())
    .systemPrompt("""
        Você é um agente de suporte ao cliente.
        Use as ferramentas disponíveis para ajudar o usuário.
        Seja educado e conciso.
        """)
    .tools(List.of(
        new CustomerLookupTool(),
        new OrderStatusTool(),
        new TicketCreateTool()
    ))
    .maxIterations(10)
    .build();
```

## Ferramentas (Tools)

### Tool Básica

```java
public class WeatherTool extends Tool {

    @Override
    public String getName() {
        return "weather";
    }

    @Override
    public String getDescription() {
        return "Obtém a previsão do tempo para uma cidade";
    }

    @Override
    public ToolResult execute(ToolInput input) {
        String city = input.get("city");
        // Lógica para buscar clima
        return ToolResult.success(Map.of(
            "city", city,
            "temperature", 25,
            "condition", "ensolarado"
        ));
    }
}
```

### Tool com Parâmetros Tipados

```java
public class CustomerLookupTool extends Tool<CustomerLookupTool.Input> {

    public record Input(String customerId) {}

    @Override
    public ToolResult execute(Input input) {
        Customer customer = customerService.findById(input.customerId());
        return ToolResult.success(customer.toMap());
    }
}
```

## Execução de Agente

```java
AgentExecutor executor = new AgentExecutor();

// Execução simples
AgentResponse response = executor.execute(
    agent,
    "Qual o status do pedido #12345?"
);

System.out.println(response.getText());
// "O pedido #12345 foi enviado e chegará em 2 dias úteis."

// Execução com streaming
executor.stream(agent, "Onde está meu pedido?")
    .subscribe(chunk -> {
        System.out.print(chunk);
    });
```

## Multi-Agent

Coordene múltiplos agentes trabalhando juntos:

```java
AgentSupervisor supervisor = AgentSupervisor.builder()
    .agents(List.of(
        researchAgent,
        writerAgent,
        reviewerAgent
    ))
    .coordinationMode(CoordinationMode.SEQUENTIAL)
    .build();

WorkflowResult result = supervisor.coordinate(
    "Crie um artigo sobre IA agentes"
);
```

## Modos de Execução

| Modo | Descrição |
|------|-----------|
| `SEQUENTIAL` | Executa tools na ordem decidida pelo LLM |
| `PARALLEL` | Executa tools independentes em paralelo |
| `DETERMINISTIC` | Saída estruturada garantida |
| `STREAMING` | Respostas em tempo real |
