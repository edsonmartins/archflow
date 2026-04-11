# Validação ArchFlow ↔ SAC Agent (Consinco)

## Contexto

Este documento valida que o framework ArchFlow tem todas as capacidades
necessárias para criar um agente equivalente ao **Consinco SAC AI Agent**
do projeto `integrall-commerce-api` (módulo `integrall-commerce-ai-agents`).

O SAC é um agente conversacional sofisticado para WhatsApp via Evolution
API com 9 fases de processamento (webhook → orchestrate → guardrails →
persona → LLM → tools → output validation → send → metrics).

**Escopo:** validamos toda a infra do agente exceto as ferramentas de
negócio (essas continuam na API do cliente).

---

## Resultado: 15/15 capacidades suportadas + integração de framework

Após implementar 5 gaps no `archflow-conversation` e adicionar o
`ConversationOrchestrator` como ponto de entrada de framework, o
ArchFlow hoje suporta nativamente todas as 15 capacidades necessárias
para replicar o SAC, e fornece uma API de uma chamada para combiná-las.

---

## Tabela de capacidades

| # | Capacidade SAC | Status | Implementação no ArchFlow |
|---|----|----|----|
| 1 | Agent orchestration (Phase 1-9) | OK | `ConversationOrchestrator` (novo), `ArchFlowAgent`, `DefaultFlowEngine`, `ConversationalAgent` |
| 2 | LLM provider abstraction | OK | `LLMProvider` enum (16 providers), `OpenRouterChatAdapter` com Ollama fallback. `ConversationOrchestrator.LlmCaller` é o ponto de plug-in. |
| 3 | System prompt versionado (Gap 1) | OK | `PromptRegistry`, `PromptVersion`, `InMemoryPromptRegistry` |
| 4 | ChatMemory sliding window (Gap 2) | OK | `WindowedChatMemoryProvider` (per-tenant, per-session) com **LRU + TTL** |
| 5 | Persistência Conversation/Message (Gap 3) | OK | `Conversation`, `Message`, `ConversationRepository`, `InMemoryConversationRepository` |
| 6 | Tool registry & execution | OK | `Tool` interface, `InterceptableToolExecutor`, interceptor chain |
| 7 | Multi-tenancy isolation | OK | `ImmutableExecutionContext.tenantId`, `TenantScheduler`, repos tenant-aware. `ConversationOrchestrator` propaga tenantId em todo o pipeline. |
| 8 | Persona resolution 4-layer (Gap 4) | OK | `Persona`, `PersonaResolver` (LLM → keyword → sticky → default) |
| 9 | Guardrails input/output (Gap 5) | OK | `AgentGuardrail`, `GuardrailChain` com **ALLOW/BLOCK/REDACT**, `ProfanityGuardrail`, `IdentificationGuardrail` (i18n via Builder), `PiiRedactionGuardrail` (input + output) |
| 10 | Scheduler cron por tenant | OK | `TenantScheduler`, `QuartzTenantScheduler`, DLQ |
| 11 | Human-in-the-Loop | OK | `ApprovalRequest`, `ApprovalRegistry`, `SuspendedConversation` |
| 12 | Streaming SSE por sessão | OK | `EventStreamRegistry`, `StreamController`, 6 domains |
| 13 | Observability metrics + audit | OK | `MetricsCollector`, `AuditLogger`, `ArchflowTracer`, Prometheus |
| 14 | REST entrypoint | OK | `AgentController`, `EventController`, `ConversationController` |
| 15 | RAG / Knowledge base | OK | `RagChainAdapter`, vector stores (Pinecone/pgvector/Redis), embeddings |

---

## ConversationOrchestrator: o ponto de integração de framework

Antes desta iteração, os 5 componentes de gap existiam isoladamente — o
produto precisava costurar tudo manualmente. Agora há um único ponto de
entrada que conecta todas as peças no pipeline canônico de SAC:

```java
var orchestrator = ConversationOrchestrator.builder()
    .prompts(promptRegistry)
    .personaResolver(personaResolver)
    .memoryProvider(memoryProvider)
    .repository(conversationRepository)
    .inputGuardrails(inputChain)
    .outputGuardrails(outputChain)
    .llmCaller(myLlmCaller)             // unica peca que toca o LLM
    .promptVariable("empresa", "Acme")
    .build();

var reply = orchestrator.process(ConversationOrchestrator.Request.of(
    "tenant-1", "user-42", null,
    "quero rastrear pedido 12345",
    Map.of("identified", true)
));

System.out.println(reply.text());        // resposta final ja sanitizada
System.out.println(reply.personaId());   // "order_tracking"
System.out.println(reply.redactionReasons()); // ["pii_detected"], se aplicavel
```

O orquestrador executa o pipeline em 11 etapas:

1. Carrega ou cria a `Conversation` (persistência).
2. Persiste a mensagem do usuário (audit log completo).
3. Roda o `GuardrailChain` de input. Se BLOCKED, persiste a resposta de
   bloqueio e retorna `BLOCKED_INPUT`.
4. Aplica o texto possivelmente redacted como input das próximas etapas.
5. Resolve a `Persona` via `PersonaResolver` e atualiza a conversa.
6. Busca o `PromptVersion` ativo da persona e renderiza com variáveis.
7. Carrega o `ChatMemory` com janela deslizante para a (tenant, conversa).
8. Chama o `LlmCaller` com `LlmRequest` (system prompt, history, persona).
9. Roda o `GuardrailChain` de output. Se BLOCKED, retorna `BLOCKED_OUTPUT`.
10. Persiste a resposta do agente (já redacted) e atualiza a memória.
11. Retorna `Reply` com `redactionReasons` agregadas de todas as etapas.

**A única peça que o produto precisa fornecer é o `LlmCaller`** — uma
interface funcional que recebe um `LlmRequest` e devolve um `LlmResponse`.
Tudo o resto é configuração declarativa.

---

## Gaps fechados (refinamentos finais)

### Gap 1 — System prompt versionado por tenant

`PromptRegistry` + `PromptVersion` + `InMemoryPromptRegistry`.

- Versões monotônicas por (tenantId, promptId), apenas uma ativa por vez.
- `activateVersion()` é atômico em relação aos leitores (corrigido após
  teste de concorrência detectar leituras transientes com 2 versões ativas).
- Templating com placeholders `{{var}}` — caracteres regex no replacement
  são corretamente escapados.
- Particionado por tenant com `ConcurrentHashMap`.

### Gap 2 — ChatMemory sliding window com eviction

`WindowedChatMemoryProvider` envolve `MessageWindowChatMemory` do LangChain4j
e adiciona:

- Cache por chave composta `tenantId:sessionId`.
- **LRU eviction** com `maxCachedSessions` (default 10.000).
- **TTL opcional** via `Duration idleTtl`. Sessões idle são expiradas no
  próximo acesso.
- `clearTenant(tenantId)` para limpeza em massa.

```java
var provider = new WindowedChatMemoryProvider(
    6,                              // janela de 6 mensagens por sessão
    1000,                           // até 1000 sessões em cache
    Duration.ofMinutes(30)          // expira após 30min idle
);
```

### Gap 3 — Conversation/Message como modelo de domínio

Records imutáveis (`Conversation`, `Message`) com factory methods e
`withStatus`/`withPersona`/`withMetadata`. Repositório
`ConversationRepository` com 9 operações tenant-scoped. Implementação
`InMemoryConversationRepository` particionada por tenant para testes.

### Gap 4 — Persona resolution com 4 camadas

`Persona` (record) + `PersonaResolver`. Estratégia de fallback:

1. **LLM classifier** (opcional, plugável via `Function<String, Optional<String>>`).
2. **Keyword regex** — patterns case-insensitive por persona.
3. **Sticky context** — última persona resolvida da conversa, cacheada
   por `conversationId`.
4. **Default** — persona registrada como fallback.

`clearSticky(conversationId)` permite resetar manualmente.

### Gap 5 — Guardrails com semântica ALLOW/BLOCK/REDACT

`GuardrailResult.Action` foi reformulado em três ações distintas:

| Ação | Significado | Comportamento da chain |
|---|---|---|
| `ALLOW` | Texto está OK | Avança para o próximo guardrail |
| `BLOCK` | Bloquear totalmente | Short-circuit. Texto original descartado, replacement vai para o usuário |
| `REDACT` | Reescrever inline | Substitui o texto e continua. Próximos guardrails veem a versão redacted |

`GuardrailChain.evaluateInput/Output` retorna um `ChainResult` com
`finalText`, `blocked`, `blockReason`, `blockMessage` e
`redactionReasons` (lista de todas as redações aplicadas).

**Implicação prática:** PII redaction não interrompe a conversa
(diferente da semântica antiga). A mensagem com CPF é mascarada e o
processamento continua. Profanity e identificação ausente continuam
bloqueando.

```java
GuardrailChain inputChain = new GuardrailChain(List.of(
    new ProfanityGuardrail(),
    new IdentificationGuardrail(),    // ou .builder() para i18n
    PiiRedactionGuardrail.inputOnly()
));

GuardrailChain.ChainResult r = inputChain.evaluateInput(message,
    Map.of("identified", true));

if (r.blocked()) {
    sendToUser(r.blockMessage());
    return;
}

callLlm(r.finalText());               // texto possivelmente redacted
log.info("redacoes aplicadas: {}", r.redactionReasons());
```

`IdentificationGuardrail` agora é totalmente i18n-friendly via Builder
(todos os patterns injetáveis); `IdentificationGuardrail.portuguese()`
mantém os defaults brasileiros.

---

## Padrão recomendado: SAC em 30 linhas

```java
// 1. Setup das dependencias (uma vez, no startup)
PromptRegistry prompts = new InMemoryPromptRegistry();
prompts.register("acme", "sac.tracking",
    "Voce e um SAC da {{empresa}}. Cliente: {{nome}}");

WindowedChatMemoryProvider memory = new WindowedChatMemoryProvider(
    6, 10_000, Duration.ofMinutes(30));

ConversationRepository repo = new InMemoryConversationRepository();

PersonaResolver personas = new PersonaResolver(List.of(
    Persona.of("order_tracking", "Tracking", "sac.tracking",
        List.of("tracking_pedido"),
        "rastrear", "\\bpedido\\b", "\\bentrega\\b")
), defaultPersona);

GuardrailChain inputChain = new GuardrailChain(List.of(
    new ProfanityGuardrail(),
    IdentificationGuardrail.portuguese()
));

GuardrailChain outputChain = new GuardrailChain(List.of(
    PiiRedactionGuardrail.outputOnly(),
    new ProfanityGuardrail()
));

ConversationOrchestrator orchestrator = ConversationOrchestrator.builder()
    .prompts(prompts)
    .personaResolver(personas)
    .memoryProvider(memory)
    .repository(repo)
    .inputGuardrails(inputChain)
    .outputGuardrails(outputChain)
    .llmCaller(req -> {
        // chame seu provider de LLM aqui (langchain4j ChatModel, HTTP, etc)
        var response = chatModel.generate(req.systemPrompt(), req.history(), req.userMessage());
        return ConversationOrchestrator.LlmResponse.of(response.text());
    })
    .promptVariable("empresa", "Acme")
    .build();

// 2. Para cada mensagem (rota REST, webhook do WhatsApp, etc)
ConversationOrchestrator.Reply reply = orchestrator.process(
    ConversationOrchestrator.Request.of(
        tenantId, userId, conversationId, message,
        Map.of("identified", customerCnpj != null)
    )
);

return reply.text();
```

---

## Testes E2E e unitários de validação

### archflow-conversation (270 testes)

| Suite | Testes | Cobertura |
|---|---|---|
| `InMemoryPromptRegistryTest` | 15 | register, versionamento, activate/rollback, multi-tenant, render |
| `WindowedChatMemoryProviderTest` | 14 | window, isolamento, LRU, TTL, clear/clearTenant |
| `InMemoryConversationRepositoryTest` | 14 | CRUD, listMessages, isolamento cross-tenant |
| `PersonaResolverTest` | 13 | 4 camadas de fallback, sticky, LLM classifier |
| `GuardrailChainTest` | 24 | ALLOW/BLOCK/REDACT, todas as 3 builtins, composição |
| `ConversationGapsE2ETest` | 13 | pipeline completo via `ConversationOrchestrator` |
| `ConversationConcurrencyStressTest` | 7 | stress test 16 threads × 200 iterações |

### archflow-agent (144 testes, incluindo 9 SAC)

`SacAgentE2ETest` valida o pipeline completo end-to-end usando todos os
componentes reais de `archflow-conversation` (sem mocks):

| Cenário | Validação |
|---|---|
| 1 | Greeting bypass não chama LLM nem ferramentas |
| 2 | Identification guardrail bloqueia "rastrear pedido" sem CNPJ |
| 3 | Tool call completo: LLM → tracking_pedido tool → resposta |
| 4 | Persona switching order_tracking → customer_support |
| 5 | Sliding window: 10 mensagens → memória mantém 6 |
| 6 | Multi-tenancy: tenants A/B com mesmo sessionId isolados |
| 7 | Human escalation com ApprovalRequest aprovado |
| 8 | 4 eventos SSE emitidos |
| 9 | 4 mensagens persistidas, listRecentMessages funciona |

### `ConversationGapsE2ETest` (13 cenários, archflow-conversation)

Exercita o `ConversationOrchestrator` real através de toda a pipeline:

1. Happy path completo com persona, prompt, memory, guardrails
2. Identification guardrail bloqueia
3. Profanity bloqueia input
4. PII redaction de output não bloqueia (continua a conversa)
5. Output profanity bloqueia
6. Persona switching entre turnos
7. Sticky persona em follow-ups
8. Sliding memory vs audit log completo
9. Multi-tenant isolation cross-pipeline
10. Prompt versioning com rollback
11. Custom guardrail plugado na chain
12. Cross-user conversation access rejeitado
13. Input PII redaction chega ao LLM já mascarada

### Bugs reais encontrados pelos testes

1. `InMemoryPromptRegistry.activateVersion()` operava sobre cópia
   defensiva — ativação era no-op silencioso. Detectado pelos testes
   unitários e corrigido.
2. `InMemoryPromptRegistry.activateVersion()` não era atômico em relação
   aos leitores — leitura concorrente podia ver duas versões ativas
   transitoriamente. Detectado pelo `ConversationConcurrencyStressTest`
   e corrigido sincronizando `getVersionsList()` no mesmo monitor.

---

## Resultado final do projeto

```
mvn clean install
```

- **BUILD SUCCESS**
- **1659 testes verdes** no projeto inteiro
- **270 testes** em `archflow-conversation` (subiu de 159 antes desta iteração)
- **144 testes** em `archflow-agent` (incluindo `SacAgentE2ETest` refatorado)
- Zero regressões nos demais módulos

---

## Arquivos criados nesta validação

### Produção (`archflow-conversation/src/main/java/.../conversation/`)

```
prompt/
  PromptVersion.java
  PromptRegistry.java
  InMemoryPromptRegistry.java
memory/
  WindowedChatMemoryProvider.java        (LRU + TTL)
domain/
  Conversation.java
  Message.java
  ConversationRepository.java
  InMemoryConversationRepository.java
persona/
  Persona.java
  PersonaResolver.java
guardrail/
  AgentGuardrail.java
  GuardrailResult.java                   (ALLOW/BLOCK/REDACT)
  GuardrailChain.java                    (retorna ChainResult)
  builtin/
    ProfanityGuardrail.java
    IdentificationGuardrail.java         (Builder + portuguese())
    PiiRedactionGuardrail.java           (input + output, redacted())
orchestrator/
  ConversationOrchestrator.java          (NOVO — wiring de framework)
```

### Testes (`archflow-conversation/src/test/java/`)

```
conversation/prompt/InMemoryPromptRegistryTest.java
conversation/memory/WindowedChatMemoryProviderTest.java
conversation/domain/InMemoryConversationRepositoryTest.java
conversation/persona/PersonaResolverTest.java
conversation/guardrail/GuardrailChainTest.java
conversation/concurrency/ConversationConcurrencyStressTest.java
conversation/e2e/ConversationGapsE2ETest.java
```

### Testes (`archflow-agent/src/test/java/`)

```
agent/e2e/SacAgentE2ETest.java           (atualizado para nova API)
agent/e2e/sac/MockChatModel.java
agent/e2e/sac/MockSacTools.java
agent/e2e/sac/MockGuardrail.java
```

---

## Conclusão

O ArchFlow está pronto para suportar agentes conversacionais sofisticados
como o SAC do Consinco **através da sua API pública de framework**, não
apenas como peças soltas:

- `ConversationOrchestrator` é o ponto de entrada canônico — produtos
  configuram dependências e processam mensagens com uma única chamada.
- Os 5 gaps foram fechados como features reais, com cobertura unitária,
  E2E e de concorrência.
- A semântica de guardrails (ALLOW/BLOCK/REDACT) reflete o uso real:
  redação não interrompe a conversa, bloqueio sim.
- LRU + TTL no chat memory provider previne vazamentos em produção.
- Identification guardrail é i18n-friendly via Builder.
- Bugs de concorrência detectados pelos próprios testes da iteração e
  corrigidos.

Combinados com as features já existentes do ArchFlow (multi-tenancy,
scheduler, streaming, escalation, RAG, observability), produtos como
o VendaX, PullWise.ai e Gestor-RQ podem ser construídos sobre o motor
sem precisar reinventar nenhuma dessas peças.
