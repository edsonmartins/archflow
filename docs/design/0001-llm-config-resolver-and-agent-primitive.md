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

### 2.6 Consumo em runtime (adapter) — IMPLEMENTADO (parcial)

O engine permanece **provider-agnostic**: ele não resolve modelo, só transporta o
resultado. Contrato via `ExecutionContext`:

- **`ExecutionKeys.LLM_RESOLVED_CONFIG`** (`archflow-model`) — chave que carrega o
  `ResolvedLLMConfig` do passo atual; `LLM_MODEL` mantém o override legado só do nome.
- **`OpenRouterChatAdapter`** passou a **consumir** essa config (model + temperature +
  **maxTokens** + apiKey/baseUrl de `additionalConfig`), com fallback para o override
  legado e depois para o default estático. A decisão foi extraída em
  `effectiveModel(context)` (testável).

**Quem popula a chave** (resolver → `context`) é responsabilidade do runner/produto
que tem o passo + o `LLMConfigResolver` (o engine não depende do provider-hub por
design). Os demais adapters de chat seguem o mesmo contrato quando adotarem o consumo
— este foi feito em um adapter como prova. **Follow-up**: popular a chave no
`StandaloneRunner`/produto e replicar o consumo nos outros adapters.

## 3. D1 — agente como primitivo (REVISADO: estender, não duplicar)

> **Correção de premissa.** O rascunho original propunha um `Agent`/`AgentDescriptor`/
> `AgentRegistry` novos. A exploração do código mostrou que **o primitivo de agente
> já existe** no archflow — criar um paralelo seria a duplicação que este trabalho
> existe para eliminar. D1 passou a ser: **estender o que existe + preencher a única
> lacuna real (roteamento por query)**.

### 3.1 O que já existe (não recriar)

| Conceito do gestor (`AbstractGestorRqAgent`) | Equivalente no archflow | Onde |
|---|---|---|
| primitivo invocável direto | `AIComponent.execute(operation, input, context)` | `archflow-model/.../ai/AIComponent.java` |
| agente / assistant / tool | `AIAgent`, `AIAssistant`, `Tool` (extends `AIComponent`) | `archflow-model/.../ai/*` |
| `getCapabilities()` | `ComponentMetadata.capabilities()` | `.../ai/metadata/ComponentMetadata.java` |
| `getRequired/OptionalParameters()` | `OperationMetadata.ParameterMetadata` (por operação) | idem |
| `getDescription()` / `getAgentType()` | `ComponentMetadata.description()` / `.type()` | idem |
| registry / descoberta | `ComponentCatalog` (register/get/search) | `archflow-plugin-api/.../catalog/` |
| `getKeywords()` | **faltava** | — |
| `canHandle(query)` / router | **faltava** | — |

### 3.2 O que foi adicionado (a lacuna real) — IMPLEMENTADO

1. **`ComponentMetadata.keywords`** (`Set<String>`) — campo novo com **construtor de
   compatibilidade** (aridade antiga) e normalização de nulo → vazio, para não
   quebrar os 6 componentes existentes.
2. **`ComponentQueryRouter`** + **`DefaultComponentQueryRouter`**
   (`archflow-plugin-api/.../catalog/`) — roteamento descriptor-driven: pontua os
   componentes do `ComponentCatalog` por keywords {@literal >} capabilities
   {@literal >} tags {@literal >} texto e devolve `route(query)` / `rank(query)`
   (com filtro opcional por `ComponentType`). É o `canHandle`/router do gestor,
   **centralizado** sobre o catálogo.
3. **Bean** `componentQueryRouter` em `ArchflowBeanConfiguration` (injetável nos
   produtos e na UI).
4. **Exemplo real**: `ConversationalAgent` ganhou `keywords` no metadata.

### 3.3 Pontos de mudança (D1) — efetivos

| Arquivo | Mudança | Status |
|---|---|---|
| `archflow-model/.../ai/metadata/ComponentMetadata.java` | + campo `keywords` + ctor compat | ✅ |
| `archflow-plugin-api/.../catalog/ComponentQueryRouter.java` | **novo** (interface + `ScoredComponent`) | ✅ |
| `archflow-plugin-api/.../catalog/DefaultComponentQueryRouter.java` | **novo** (scoring determinístico) | ✅ |
| `archflow-api/.../config/ArchflowBeanConfiguration.java` | bean `componentQueryRouter` | ✅ |
| `archflow-plugins/.../agents/ConversationalAgent.java` | keywords de exemplo | ✅ |

### 3.4 Tier `agent` da cadeia de herança (D2) — IMPLEMENTADO

O override de LLM por agente vem da config declarada do componente
(`ComponentMetadata.properties()`). Implementado:

- **`LLMConfigPatch.fromMap(Map)`** (archflow-model) — coerção canônica Map→patch
  (provider/model/temperature/maxTokens/timeout/additionalConfig), lenient (aceita
  `Number` ou String numérica), ignora chaves não-LLM. `SerializableStep.getLLMPatch()`
  passou a **reusá-la** (dedup do tier `step`).
- **`LLMResolutionRequest.forStep(..., AIComponent agent)`** — overload que preenche
  o `agentPatch` de `agent.getMetadata().properties()`. Precedência efetiva agora
  completa: **step {@literal >} agent {@literal >} flow {@literal >} tenant {@literal >} platform**.

### 3.5 Formato conversacional (tool-calling) — follow-up

O loop `context → guardrail-in → LLM → tools → follow-up → guardrail-out` do
`LLMAgentOrchestratorImpl` (integrall) e um `ToolRegistry` continuam como fase
seguinte; o `GuardrailChain` já existe no archflow para a parte de guardrails.

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
