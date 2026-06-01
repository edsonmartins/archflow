# Design 0001 — `LLMConfigResolver` mergeable + primitivo de Agente

> Implementa **D1** e **D2** da [ADR-0001](../adr/0001-agent-runtime-substrate.md).
> Base concreta: `DynamicChatModelResolver` do `gestor-rq`.

## 1. Estado atual (verbatim)

| Conceito | Onde | Limitação |
|---|---|---|
| Config de LLM por flow | `archflow-model/.../config/LLMConfig.java:8` | record "cheio": `model`, `temperature` (double), `maxTokens` (int), `timeout` (long), `additionalConfig`. Sem override parcial. |
| Exposição da config | `FlowConfiguration.getLLMConfig()` (`.../config/FlowConfiguration.java:29`) | só nível flow |
| Step | `FlowStep` (`.../flow/FlowStep.java:15`) | **não carrega** config de modelo |
| Construção do modelo | `LLMProviderHub` + `LLMProviderConfig` (`archflow-langchain4j-provider-hub/...`) | `LLMProviderConfig` usa `Integer/Double` (já nuláveis), cacheia por `configId`; chave registrada estaticamente, sem resolução por tenant |
| Agente | `ArchFlowAgent` (`archflow-agent/.../ArchFlowAgent.java:39`) | é um **executor de flows** (`executeFlow`), não um primitivo de agente. `AgentConfig` (`.../config/AgentConfig.java:10`) é config de runtime (concorrência, plugins), não de um agente individual. |

Referência concreta a generalizar — `gestor-rq`:
`DynamicChatModelResolver.resolve(governance, fallback, maxOutputTokensOverride, modelOverride)`
(`.../infrastructure/config/ai/DynamicChatModelResolver.java:65`). Pontos-chave:
- override de `model` e `maxTokens` **só para aquela resolução** (linhas 77, 96-98);
- chave por tenant: `per-tenant > global` (`resolveApiKey`, linha 132);
- cache por `provider:baseUrl:model:apiKeyHash:maxTokens` (linha 101);
- fallback ao bean estático quando faltam dados (linhas 67-91).

## 2. D2 — `LLMConfig` mergeable + resolver

### 2.1 Config como patch parcial

Hoje `LLMConfig` força todos os campos. Para herança, cada nível só deve
preencher o que sobrescreve. Introduzir um tipo de **patch** com campos
opcionais, mantendo `LLMConfig` atual como o **resolvido** (todos preenchidos).

```java
// archflow-model/.../config/LLMConfigPatch.java  (NOVO)
public record LLMConfigPatch(
        Optional<String>  model,
        Optional<String>  provider,      // novo: provider pode variar por agente
        OptionalDouble    temperature,
        OptionalInt       maxTokens,
        OptionalLong      timeout,
        Map<String, Object> additionalConfig   // merge raso (override por chave)
) {
    public static LLMConfigPatch empty() { ... }

    /** Aplica este patch sobre um pai já resolvido. */
    public ResolvedLLMConfig applyOver(ResolvedLLMConfig parent) { ... }
}
```

```java
// archflow-model/.../config/ResolvedLLMConfig.java  (NOVO — substitui o uso "cru" de LLMConfig)
public record ResolvedLLMConfig(
        String provider,
        String model,
        double temperature,
        int maxTokens,
        long timeout,
        Map<String, Object> additionalConfig
) {}
```

> **Compatibilidade.** Como o archflow ainda **não tem versão em produção**, não
> usamos `@Deprecated` (cerimônia sem propósito). `LLMConfig` permanece como a
> config declarada de fluxo e ganha apenas `LLMConfig#toPatch()`, consumido pelo
> default de `FlowConfiguration.getLLMPatch()`. Quando quisermos, dá para colapsar
> `LLMConfig` em `ResolvedLLMConfig` sem dívida de compatibilidade.
>
> **Status: IMPLEMENTADO** (branch `feat/llm-config-resolver`).

### 2.2 Cadeia de resolução

```
ResolvedLLMConfig efetivo =
    platformDefault
      .merge(tenantDefault)     // do GovernanceSnapshot (D3)
      .merge(flowConfig)        // FlowConfiguration.getLLMPatch()
      .merge(agentConfig)       // AgentDescriptor.llmPatch()
      .merge(stepOverride)      // FlowStep.getLLMPatch()
```

Cada `merge` é `patch.applyOver(acumulado)`. Precedência: **mais específico
ganha** (step > agent > flow > tenant > platform).

### 2.3 Interface do resolver

```java
// archflow-langchain4j-provider-hub/.../LLMConfigResolver.java  (NOVO)
public interface LLMConfigResolver {

    /** Resolve a config efetiva percorrendo a cadeia de patches. */
    ResolvedLLMConfig resolve(LLMResolutionRequest request);

    /** Resolve e devolve o ChatModel pronto (delega ao LLMProviderHub). */
    ChatModel resolveModel(LLMResolutionRequest request);
}

public record LLMResolutionRequest(
        String tenantId,
        ResolvedLLMConfig platformDefault,
        LLMConfigPatch tenantDefault,   // vem do GovernanceSnapshot
        LLMConfigPatch flowPatch,
        LLMConfigPatch agentPatch,
        LLMConfigPatch stepPatch
) {}
```

### 2.4 Resolução de chave por tenant (SPI)

A chave **não** mora no archflow (cada produto guarda a sua de um jeito). Definir
SPI; o produto implementa. Espelha `resolveApiKey` do gestor.

```java
// archflow-langchain4j-provider-hub/.../TenantKeyResolver.java  (NOVO, SPI)
public interface TenantKeyResolver {
    /** Chave para (tenant, provider); Optional.empty() => cai no global/fallback. */
    Optional<String> resolveApiKey(String tenantId, String provider);
}
```

O `LLMConfigResolver` aplica: `tenantKey.orElse(globalKey)`. Cache do
`LLMProviderHub` deve usar **hash** da chave (como o gestor faz, linha 128), nunca
a chave em claro.

### 2.5 Pontos de mudança (D2)

| Arquivo | Mudança |
|---|---|
| `archflow-model/.../config/LLMConfigPatch.java` | **novo** |
| `archflow-model/.../config/ResolvedLLMConfig.java` | **novo** |
| `archflow-model/.../config/LLMConfig.java` | + `toPatch()` (sem `@Deprecated` — sem versão em prod) |
| `archflow-model/.../config/FlowConfiguration.java` | adicionar `default LLMConfigPatch getLLMPatch()` (default = derivado do `getLLMConfig()` para compat) |
| `archflow-model/.../flow/FlowStep.java` | adicionar `default LLMConfigPatch getLLMPatch() { return LLMConfigPatch.empty(); }` (default não-quebra implementações) |
| `archflow-langchain4j-provider-hub/.../LLMConfigResolver.java` | **novo** (interface) |
| `archflow-langchain4j-provider-hub/.../DefaultLLMConfigResolver.java` | **novo** (impl; cache por hash; fallback) |
| `archflow-langchain4j-provider-hub/.../TenantKeyResolver.java` | **novo** (SPI) |
| `archflow-langchain4j-provider-hub/.../LLMProviderHub.java` | expor entrada que aceite `ResolvedLLMConfig`+chave já resolvida |

## 3. D1 — primitivo de Agente

### 3.1 Contrato

Extrai o denominador comum de `AbstractGestorRqAgent` (descriptor + execução com
métricas + `canHandle`) e do orquestrador tool-calling do integrall.

```java
// archflow-agent/.../agent/Agent.java  (NOVO)
public interface Agent {
    AgentDescriptor descriptor();
    AgentResult execute(AgentRequest request);   // invocação DIRETA, sem Flow
}

// archflow-agent/.../agent/AgentDescriptor.java  (NOVO)
public record AgentDescriptor(
        String agentType,                 // ex.: "ticket_classification"
        String description,
        List<String> capabilities,
        List<String> keywords,
        List<String> requiredParameters,
        List<String> optionalParameters,
        LLMConfigPatch llmPatch           // override de modelo POR AGENTE (D2)
) {
    /** Score 0..1 de aderência a uma query (keyword match — do gestor). */
    public double canHandle(String query) { ... }
}

// archflow-agent/.../agent/AgentRequest.java / AgentResult.java  (NOVO)
//   AgentRequest: tenantId, sessionId, input Map, GovernanceSnapshot
//   AgentResult : success, output, error, executionTimeMs, agentType, sessionId
```

### 3.2 Dois formatos sobre o mesmo contrato

- `AbstractTaskAgent` (single-shot, saída estruturada/JSON) — molde de
  `AbstractGestorRqAgent`: `executeWithMetrics`, `validateInput`,
  `buildSystemPrompt`, parse tolerante de JSON.
- `AbstractConversationalAgent` (loop tool-calling) — molde de
  `LLMAgentOrchestratorImpl`: `context → guardrail-in → LLM → tools → follow-up →
  guardrail-out`, com `ToolRegistry`.

Ambos obtêm o `ChatModel` via `LLMConfigResolver.resolveModel(...)` montando o
`LLMResolutionRequest` com `descriptor().llmPatch()` como `agentPatch`. **É aqui
que a dor central morre:** o agente de extração de preço declara seu
`maxTokens`/`model` no próprio descriptor, sem afetar os demais.

### 3.3 Registry e invocação direta

```java
// archflow-agent/.../agent/AgentRegistry.java  (NOVO)
public interface AgentRegistry {
    void register(Agent agent);
    Optional<Agent> byType(String agentType);
    /** Roteamento por keyword/capability (canHandle), opcional. */
    Optional<Agent> route(String query);
    List<AgentDescriptor> list();
}
```

`ArchFlowAgent` (o executor de flows atual) **não muda de papel**; ganha apenas a
capacidade de, num `FlowStep` do tipo `agent`, resolver o `Agent` pelo
`AgentRegistry` e invocá-lo — fazendo do flow **um consumidor** do primitivo, como
decidido em D1. A invocação direta (sem flow) é o caminho que `gestor-rq`/
`integrall` usariam.

### 3.4 Pontos de mudança (D1)

| Arquivo | Mudança |
|---|---|
| `archflow-agent/.../agent/Agent.java` | **novo** (contrato) |
| `archflow-agent/.../agent/AgentDescriptor.java` | **novo** |
| `archflow-agent/.../agent/AgentRequest.java`, `AgentResult.java` | **novo** |
| `archflow-agent/.../agent/AbstractTaskAgent.java` | **novo** (molde gestor) |
| `archflow-agent/.../agent/AbstractConversationalAgent.java` | **novo** (molde integrall) |
| `archflow-agent/.../agent/AgentRegistry.java` + `DefaultAgentRegistry.java` | **novo** |
| `archflow-agent/.../ArchFlowAgent.java` | ponto de integração flow→agent (consumir registry no step `agent`) |

> Nota: `AgentConfig` (`.../config/AgentConfig.java`) permanece como config de
> runtime do executor. **Não** confundir com `AgentDescriptor` (metadados de um
> agente individual). Nomes distintos de propósito.

## 4. Ordem de implementação

1. `LLMConfigPatch` + `ResolvedLLMConfig` + `LLMConfig#toResolved()` (compat).
2. `LLMConfigResolver` + `DefaultLLMConfigResolver` + `TenantKeyResolver` SPI
   (porte direto do `DynamicChatModelResolver`).
3. `getLLMPatch()` default em `FlowStep`/`FlowConfiguration`.
4. Contrato `Agent` + `AgentDescriptor` + `AbstractTaskAgent`.
5. `AgentRegistry` + integração no `ArchFlowAgent`.
6. `AbstractConversationalAgent` + `ToolRegistry` (pode ir numa fase seguinte).

## 5. Plano de testes

- **Precedência da cadeia**: tabela de casos garantindo step > agent > flow >
  tenant > platform, incluindo override parcial (só `maxTokens`).
- **Fallback**: patch vazio em todos os níveis → `platformDefault`.
- **Chave por tenant**: `TenantKeyResolver` retorna chave → usada; vazio → global.
- **Cache**: chaves distintas por `maxTokens`/`model` efetivos (regressão do bug
  do gestor); chave nunca em claro no cache key.
- **`canHandle`**: paridade com o comportamento do `AbstractGestorRqAgent`
  (`.../ia/core/AbstractGestorRqAgent.java:81`).
- **Compat**: flow legado usando só `getLLMConfig()` continua resolvendo igual.

## 6. Fora de escopo (fica no produto)

Tools de negócio, builders de contexto de domínio, parse específico de payload,
persistência da chave/governança, e toda a lista de harness da ADR-0001.
