---
title: Streaming API
sidebar_position: 4
slug: api-streaming
---

# Streaming API

## Archflow Streaming Protocol

Protocolo para streaming de respostas AI via Server-Sent Events (SSE).

### Formato do Evento

```typescript
interface ArchflowEvent {
  envelope: {
    domain: "chat" | "interaction" | "thinking" | "tool" | "audit"
    type: "message" | "form" | "error" | "delta"
    id: string
    timestamp: number
  }
  data: {
    content?: string          // Para domain="chat"
    formId?: string           // Para domain="interaction"
    toolName?: string         // Para domain="tool"
    metadata?: Record<string, unknown>
  }
}
```

### Domínios

| Domínio | Descrição |
|---------|-----------|
| `chat` | Mensagens do modelo (deltas e completas) |
| `interaction` | Formulários para interação humana |
| `thinking` | Processo de raciocínio (o1, etc.) |
| `tool` | Execução de ferramentas |
| `audit` | Eventos de auditoria e tracing |

## Servidor Streaming

### Controller SSE

```java
@RestController
@RequestMapping("/api/stream")
public class StreamingController {

    private final StreamingService streamingService;

    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestParam String message) {

        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        String executionId = UUID.randomUUID().toString();

        streamingService.streamChat(message, executionId)
            .doOnNext(event -> {
                emitter.send(SseEmitter.event()
                    .name(event.getEnvelope().getDomain())
                    .data(event.toJson()));
            })
            .doOnComplete(() -> emitter.complete())
            .doOnError(emitter::completeWithError)
            .subscribe();

        return emitter;
    }
}
```

### Event Types

```java
public enum ArchflowEventType {
    // Chat domain
    DELTA,          // Chunk de texto
    MESSAGE,        // Mensagem completa

    // Interaction domain
    FORM_REQUEST,   // Solicita input do usuário
    FORM_SUBMIT,    // Usuário submeteu form

    // Tool domain
    TOOL_START,     // Tool iniciou execução
    TOOL_COMPLETE,  // Tool completou
    TOOL_ERROR,     // Tool falhou

    // Audit domain
    TRACE_START,    // Início de operação
    TRACE_END,      // Fim de operação
}
```

## Cliente Streaming

### JavaScript/TypeScript

```typescript
import { ArchflowStreamClient } from '@archflow/streaming';

const client = new ArchflowStreamClient('http://localhost:8080/api');

// Streaming de chat
const stream = await client.chat({
  message: 'Explique quantum computing',
  agentId: 'science-tutor'
});

for await (const event of stream) {
  switch (event.envelope.domain) {
    case 'chat':
      if (event.envelope.type === 'delta') {
        // Chunk de texto
        process.stdout.write(event.data.content);
      }
      break;

    case 'tool':
      console.log(`Tool: ${event.data.toolName}`);
      break;

    case 'interaction':
      // Exibir formulário ao usuário
      showForm(event.data);
      break;
  }
}
```

### React Hook

```typescript
import { useArchflowStream } from '@archflow/react';

function ChatComponent() {
  const { events, error, streaming, send } = useArchflowStream();

  return (
    <div>
      {events.map((event, i) => (
        <EventItem key={i} event={event} />
      ))}
      <button
        onClick={() => send('Explique IA')}
        disabled={streaming}>
        Enviar
      </button>
    </div>
  );
}
```

## Suspend/Resume

### Fluxo de Interação

```
┌─────────────────────────────────────────────────────────────┐
│  1. User → "Crie uma conta"                                 │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│  2. AI → [SuspendEvent com form]                            │
│     {                                                        │
│       domain: "interaction",                                 │
│       type: "form",                                          │
│       data: {                                                │
│         formId: "account-form",                              │
│         fields: [                                            │
│           { name: "email", type: "email", required: true }, │
│           { name: "password", type: "password" }            │
│         ]                                                    │
│       }                                                      │
│     }                                                        │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│  3. User → [Preenche e submete form]                        │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│  4. AI → [Resume com dados]                                 │
│     "Conta criada para {email}!"                            │
└─────────────────────────────────────────────────────────────┘
```

### Implementação

```java
@Service
public class ConversationManager {

    public Flux<ArchflowEvent> streamWithSuspension(String message) {

        return agentExecutor.stream(agent, message)
            .switchMap(event -> {
                if (isSuspensionNeeded(event)) {
                    // Cria suspensão
                    SuspendedConversation suspended = createSuspension(event);

                    // Emite evento de interação
                    return Flux.just(ArchflowEvent.builder()
                        .domain("interaction")
                        .type("form")
                        .data(Map.of(
                            "formId", suspended.getId(),
                            "fields", suspended.getForm()
                        ))
                        .build());
                }
                return Flux.just(event);
            });
    }

    public Flux<ArchflowEvent> resume(String suspensionId, Map<String, Object> data) {
        SuspendedConversation suspended = repository.findById(suspensionId);

        return agentExecutor.stream(
            agent,
            suspended.getAgentContext().withData(data)
        );
    }
}
```

## Thinking Process

Para modelos com raciocínio (o1, etc.):

```java
// Evento de thinking
ArchflowEvent thinking = ArchflowEvent.builder()
    .domain("thinking")
    .type("delta")
    .data(Map.of(
        "content", "Deixe-me analisar isso...",
        "stage", "reasoning"
    ))
    .build();

// Cliente pode mostrar spinner ou pensamento
```

## Web Component

```html
<archflow-chat
  api-base="http://localhost:8080/api"
  agent-id="support"
  theme="dark"
  on-event="handleEvent">
</archflow-chat>

<script>
  function handleEvent(event) {
    if (event.detail.envelope.domain === 'interaction') {
      // Exibir form
      showForm(event.detail.data);
    }
  }
</script>
```
