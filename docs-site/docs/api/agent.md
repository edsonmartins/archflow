---
title: Agent API
sidebar_position: 2
slug: api-agent
---

# Agent API

## Agent

Interface principal para criação de agentes AI.

### Builder

```java
Agent agent = Agent.builder()
    .id("my-agent")
    .name("Meu Agente")
    .description("Descrição do agente")
    .llm(chatLanguageModel)
    .systemPrompt("Você é um assistente útil")
    .tools(List.of(
        new Tool1(),
        new Tool2()
    ))
    .memory(ChatMemory.withMaxMessages(100))
    .maxIterations(10)
    .temperature(0.7)
    .build();
```

### Propriedades

| Propriedade | Tipo | Padrão | Descrição |
|-------------|------|--------|-----------|
| `id` | String | - | Identificador único |
| `name` | String | - | Nome do agente |
| `description` | String | - | Descrição |
| `llm` | ChatLanguageModel | - | Modelo de linguagem |
| `systemPrompt` | String | - | Prompt do sistema |
| `tools` | List\<Tool\> | [] | Ferramentas disponíveis |
| `memory` | ChatMemory | - | Memória de conversação |
| `maxIterations` | int | 10 | Máximo de iterações |
| `temperature` | double | 0.7 | Criatividade (0-1) |

## AgentExecutor

Executor de agentes.

### Métodos

```java
public interface AgentExecutor {

    // Execução síncrona
    AgentResponse execute(Agent agent, String message);

    // Execução com contexto
    AgentResponse execute(Agent agent, AgentContext context);

    // Execução em streaming
    Flux<String> stream(Agent agent, String message);

    // Execução assíncrona
    CompletableFuture<AgentResponse> executeAsync(
        Agent agent,
        String message
    );
}
```

### Uso

```java
AgentExecutor executor = new AgentExecutor();

// Execução simples
AgentResponse response = executor.execute(agent, "Olá!");
System.out.println(response.getText());

// Com histórico
ChatHistory history = new ChatHistory();
history.addUser("Olá!");
history.addAssistant("Oi! Como posso ajudar?");
history.addUser("Qual o tempo hoje?");

AgentResponse response = executor.execute(agent, history);

// Streaming
executor.stream(agent, "Conte uma história")
    .subscribe(chunk -> System.out.print(chunk));
```

## Tool

Interface para criação de ferramentas.

### Implementação

```java
public class MyTool extends Tool<MyTool.Input> {

    public record Input(String param1, int param2) {}

    @Override
    public String getName() {
        return "my-tool";
    }

    @Override
    public String getDescription() {
        return "Descrição da ferramenta";
    }

    @Override
    public Map<String, ParameterSchema> getParameters() {
        return Map.of(
            "param1", ParameterSchema.string("Primeiro parâmetro")
                .required()
                .build(),
            "param2", ParameterSchema.integer("Segundo parâmetro")
                .defaultValue(10)
                .build()
        );
    }

    @Override
    public ToolResult execute(Input input) {
        // Lógica da ferramenta
        return ToolResult.success(Map.of(
            "result", "sucesso"
        ));
    }
}
```

### ParameterSchema

Define parâmetros da ferramenta.

```java
ParameterSchema.string("Nome")
    .description("Descrição")
    .required()
    .defaultValue("valor")
    .enumValues(List.of("a", "b", "c"))
    .build();

ParameterSchema.integer("Idade")
    .min(0)
    .max(150)
    .build();

ParameterSchema.number("Preço")
    .min(0.0)
    .build();

ParameterSchema.boolean("Ativo")
    .build();

ParameterSchema.array("Itens")
    .items(ParameterSchema.string("Item"))
    .build();

ParameterSchema.object("Configuração")
    .properties(Map.of(
        "key", ParameterSchema.string("Chave"),
        "value", ParameterSchema.string("Valor")
    ))
    .build();
```

## AgentResponse

Resposta da execução de um agente.

```java
public class AgentResponse {

    // Texto da resposta
    public String getText();

    // Mensagens brutas do LLM
    public List<ChatMessage> getMessages();

    // Ferramentas chamadas
    public List<ToolCall> getToolCalls();

    // Tokens utilizados
    public TokenUsage getTokenUsage();

    // Duração
    public Duration getDuration();

    // Metadados
    public Map<String, Object> getMetadata();
}
```

## ChatMemory

Gerencia memória de conversação.

### Tipos

```java
// Limite por número de mensagens
ChatMemory.withMaxMessages(100);

// Limite por tokens
ChatMemory.withMaxTokens(4000);

// Memória com resumo
ChatMemory.withSummary(
    maxMessagesBeforeSummary: 50,
    summaryPrompt: "Resuma a conversa:"
);

// Memória persistente
ChatMemory.builder()
    .maxMessages(100)
    .persistence(true)
    .storage(new RedisChatMemoryStorage())
    .build();
```

### Uso

```java
ChatMemory memory = ChatMemory.withMaxMessages(100);

memory.add(UserMessage.from("Olá"));
memory.add(AssistantMessage.from("Oi!"));

Agent agent = Agent.builder()
    .memory(memory)
    .build();

// Acessar histórico
List<ChatMessage> messages = memory.getMessages();
```

## AgentContext

Contexto de execução do agente.

```java
AgentContext context = AgentContext.builder()
    .message("Olá")
    .userId("user-123")
    .sessionId("session-456")
    .variables(Map.of(
        "name", "João"
    ))
    .metadata(Map.of(
        "source", "webchat"
    ))
    .build();

AgentResponse response = executor.execute(agent, context);
```

## AgentSupervisor

Coordenador de múltiplos agentes.

```java
AgentSupervisor supervisor = AgentSupervisor.builder()
    .mode(CoordinationMode.SEQUENTIAL)
    .agents(List.of(
        agent1,
        agent2,
        agent3
    ))
    .systemPrompt("Você é o coordenador")
    .build();

String result = supervisor.coordinate("Execute a tarefa");
```

### Modos de Coordenação

| Modo | Descrição |
|------|-----------|
| `SEQUENTIAL` | Agentes executam em sequência |
| `PARALLEL` | Agentes executam em paralelo |
| `HIERARCHICAL` | Supervisor delega para sub-agentes |
| `CONSENSUS` | Agentes votam na melhor resposta |
