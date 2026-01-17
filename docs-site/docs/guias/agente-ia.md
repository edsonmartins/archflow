---
title: Agente AI
sidebar_position: 2
slug: agente-ia
---

# Criando um Agente AI

Neste guia, você aprenderá a criar um agente AI que pode usar ferramentas para realizar tarefas.

## O que é um Agente AI?

Um agente AI é um sistema que usa um modelo de linguagem para:
1. **Raciocinar** sobre o objetivo do usuário
2. **Planejar** quais ferramentas usar
3. **Executar** as ações necessárias
4. **Responder** com o resultado

## Guia: Agente de Suporte Técnico

### Passo 1: Defina as Ferramentas

```java
// Ferramenta para buscar dados do cliente
@Component
public class CustomerLookupTool extends Tool {

    private final CustomerRepository customerRepo;

    @Override
    public String getName() {
        return "customer_lookup";
    }

    @Override
    public String getDescription() {
        return "Busca informações de um cliente pelo ID ou email";
    }

    @Override
    public Map<String, ParameterSchema> getParameters() {
        return Map.of(
            "identifier", ParameterSchema.string("ID ou email do cliente")
                .required()
                .build()
        );
    }

    @Override
    public ToolResult execute(ToolInput input) {
        String identifier = input.getString("identifier");

        Customer customer = customerRepo.findByIdentifier(identifier);
        if (customer == null) {
            return ToolResult.error("Cliente não encontrado");
        }

        return ToolResult.success(Map.of(
            "id", customer.getId(),
            "name", customer.getName(),
            "email", customer.getEmail(),
            "tier", customer.getTier()
        ));
    }
}

// Ferramenta para buscar status de pedido
@Component
public class OrderStatusTool extends Tool {

    private final OrderRepository orderRepo;

    @Override
    public String getName() {
        return "order_status";
    }

    @Override
    public String getDescription() {
        return "Busca o status atual de um pedido";
    }

    @Override
    public Map<String, ParameterSchema> getParameters() {
        return Map.of(
            "orderId", ParameterSchema.string("ID do pedido")
                .required()
                .build()
        );
    }

    @Override
    public ToolResult execute(ToolInput input) {
        String orderId = input.getString("orderId");

        Order order = orderRepo.findById(orderId);
        return ToolResult.success(Map.of(
            "orderId", order.getId(),
            "status", order.getStatus(),
            "estimatedDelivery", order.getEstimatedDelivery()
        ));
    }
}
```

### Passo 2: Crie o Agente

```java
@Configuration
public class AgentConfig {

    @Bean
    public Agent supportAgent(
            ChatLanguageModel llm,
            CustomerLookupTool customerTool,
            OrderStatusTool orderTool) {

        return Agent.builder()
            .id("support-agent")
            .name("Agente de Suporte")
            .llm(llm)
            .systemPrompt("""
                Você é um agente de suporte ao cliente útil e educado.

                Suas responsabilidades:
                - Ajudar clientes a encontrar informações
                - Consultar status de pedidos
                - Oferecer soluções para problemas

                Diretrizes:
                - Seja sempre cordial
                - Use as ferramentas disponíveis para buscar informações
                - Nunca invente dados que você não tem acesso
                """)
            .tools(List.of(customerTool, orderTool))
            .maxIterations(5)
            .build();
    }
}
```

### Passo 3: Crie o Executor

```java
@Service
public class SupportService {

    private final AgentExecutor executor;
    private final Agent supportAgent;

    public String handleCustomerMessage(String message) {
        AgentResponse response = executor.execute(
            supportAgent,
            message
        );

        return response.getText();
    }

    public Flux<String> handleCustomerMessageStream(String message) {
        return executor.stream(supportAgent, message);
    }
}
```

### Passo 4: API REST

```java
@RestController
@RequestMapping("/api/support")
public class SupportController {

    private final SupportService supportService;

    @PostMapping
    public ResponseEntity<String> chat(@RequestBody String message) {
        String response = supportService.handleCustomerMessage(message);
        return ResponseEntity.ok(response);
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestParam String message) {
        return supportService.handleCustomerMessageStream(message);
    }
}
```

## Testando

```bash
curl -X POST http://localhost:8080/api/support \
  -H "Content-Type: text/plain" \
  -d "Qual o status do meu pedido #12345?"
```

Resposta esperada:
```
Vou verificar o status do seu pedido. Um momento, por favor.

O pedido #12345 está atualmente "Em trânsito" com previsão de entrega para 15/01/2026.
```

## Melhorias

### 1. Memória do Agente

```java
Agent.builder()
    .id("support-agent-with-memory")
    .memory(ChatMemory.withMaxMessages(100))
    .build();
```

### 2. Guardrails

```java
@Component
public class SupportGuardrails implements ToolInterceptor {

    @Override
    public void beforeExecute(ToolContext context) {
        // Verifica permissões
        String tool = context.getTool().getName();
        if (tool.equals("customer_lookup") && !isAuthenticated()) {
            throw new UnauthorizedException("Autenticação necessária");
        }
    }
}
```

### 3. Rastreamento

```java
@Component
public class TracingInterceptor implements ToolInterceptor {

    @Override
    public void afterExecute(ToolContext context, ToolResult result) {
        Span.current().tag("tool.name", context.getTool().getName());
        Span.current().tag("tool.duration", context.getDuration() + "ms");
    }
}
```

## Próximos Passos

- [rag](/docs/guias/rag) - Adicione conhecimento ao seu agente
- [multi-agente](/docs/guias/multi-agente) - Coordene múltiplos agentes
