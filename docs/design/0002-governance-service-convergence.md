# Design 0002 — Convergência de Governança: extraindo o supertipo comum

> Implementa **D3** da [ADR-0001](../adr/0001-agent-runtime-substrate.md).
> Insumo: as **duas implementações independentes** de `AgentGovernanceService` —
> `gestor-rq` e `integrall-commerce-api` — lidas verbatim.

## 1. Por que isto existe

`gestor-rq` e `integrall-commerce-api` construíram, separadamente, o **mesmo
serviço** com o **mesmo propósito** ("resolve configurações de governança
efetivas combinando defaults e registros por tenant"). O cabeçalho do próprio
`gestor-rq` admite: *"Baseado no padrão do integrall-commerce-api."* As cópias já
**divergiram** em pontos que quebram interoperabilidade. Esta é a prova de campo
de que governança pertence ao archflow.

## 2. Comparativo lado a lado

| Dimensão | `gestor-rq` | `integrall-commerce-api` |
|---|---|---|
| Classe | `application.service.ia.governance.AgentGovernanceService` | `agents.core.governance.AgentGovernanceService` |
| Persistência | `AgentGovernanceProfileJpaRepository` **direto** | `AgentGovernanceProfileService` (serviço de domínio) |
| Forma da config no banco | `settingsJson` (String) → `parseSettings()` via `objectMapper.readValue` (linha 248) | objeto já desserializado em `profile.getSettings()`; **clone** via JSON round-trip `cloneSettings()` (linha 218) |
| Entrada de resolução | `resolve()`, `resolve(tenantId)`, `resolve(Map metadata)`, `resolveByProfileId()` | `resolve(ConversationContext)`, `resolve(orgCode)`, `resolveByProfileId()`, `resolveByTenantId()` |
| Prioridade | tenantId direto | **profileId primeiro** (se houver no contexto), senão orgCode (linha 43) |
| Match de tenant | **hack base64**: tenta UUID, depois base64(orgCode), depois decode base64 (linhas 159-178) | sem hack; profileId-first resolve o problema |
| Cache | **Caffeine** (`maximumSize(500)`, `expireAfterWrite(5min)` — hardcoded no builder, **apesar** de `@Value cache-ttl-seconds:300`) | **ConcurrentHashMap** + `record CachedSnapshot(snapshot, expiresAt)` com TTL de `properties.getCacheTtlSeconds()` (linha 231) |
| Defaults | `buildDefaultSettings()` hardcoded + `@Value` (linha 237) | `properties.getDefaults()` (bean `AgentGovernanceProperties`) (linha 160) |
| Governança desligada | retorna `buildDefaultSnapshot(...)` (linha 84) | retorna **`null`** (linha 39) ⚠️ |
| `GovernanceSnapshot` | `orgCode, settings, fromDatabase, resolvedAt` | idem **+ `profileId`** (linha 144) |
| Campo do modelo em `LLMSettings` | `model` (linha 271) | **`preferredModel`** ⚠️ |
| Auditoria | `SecurityAuditService` opcional em parse failure (linha 256) | — |

### Divergências que já machucam (⚠️)

1. **`model` vs `preferredModel`** — o mesmo conceito tem nome diferente. Um JSON
   de governança de um produto **não desserializa** corretamente no outro.
2. **Desligado → `null` vs defaults** — contratos opostos. Quem consome precisa
   tratar `null` em um e nunca-null no outro. Fonte clássica de NPE ao portar.
3. **base64 hack** — vazamento de uma decisão de persistência (admin grava
   tenant em base64) para dentro do resolver. Sintoma de falta de contrato.
4. **Caffeine TTL hardcoded ignora a property** — bug latente no gestor; o
   integrall respeita a property. Comportamento de cache diverge silenciosamente.

## 3. O que é genuinamente comum (o supertipo)

Apesar das divergências, o **esqueleto é idêntico**:

```
resolve(identidade) → [cache hit?] → carrega profile (store) →
  parse/clone settings → monta Snapshot(fromDatabase) → cacheia
  ↳ se ausente/desligado → Snapshot de defaults
```

E a **estrutura de settings** é quase a mesma (ver `AgentGovernanceSettings` do
gestor: `agentEnabled`, `customSystemPrompt`, e seções `Guardrails`,
`RateLimitSettings`, `ContextSettings`, `EscalationSettings`, `LLMSettings`,
`MediaProcessingSettings`, `TenantIdentity`, `WhatsAppSettings`,
`ModerationSettings`, `HallucinationDetectionSettings`, `settingsVersion`).

## 4. Supertipo proposto (em `archflow-conversation` / `archflow-agent`)

Mesma filosofia do `PromptRegistry`: **archflow define o contrato; o produto
persiste**. Settings é **documento JSON versionado** (D3), não schema normalizado.

### 4.1 Settings genérico (núcleo + extensão)

```java
// archflow-agent/.../governance/GovernanceSettings.java  (NOVO)
@JsonIgnoreProperties(ignoreUnknown = true)   // tolerância: ignora campos do produto
public class GovernanceSettings {
    private boolean agentEnabled = true;
    private String  agentDisabledReason;
    private String  customSystemPrompt;

    // Seções GENÉRICAS (denominador comum dos dois produtos)
    private GuardrailSettings   guardrails   = new GuardrailSettings();
    private RateLimitSettings   rateLimit    = new RateLimitSettings();
    private ContextSettings     context      = new ContextSettings();
    private EscalationSettings  escalation   = new EscalationSettings();
    private LLMSettings         llm          = new LLMSettings();
    private MediaSettings       media        = new MediaSettings();

    // Versionamento (lição: nunca normalizar, sempre versionar o documento)
    private long settingsVersion = 1L;
    private Instant lastModified;
    private String  lastModifiedBy;

    // Extensão livre do produto (TenantIdentity, WhatsApp, etc. ficam aqui)
    private Map<String, Object> extensions = new HashMap<>();
}
```

> **Decisão sobre o campo do modelo:** padronizar em **`model`** (não
> `preferredModel`). O `LLMSettings` genérico reusa o **`LLMConfigPatch`** do
> Design 0001 — governança vira a fonte do `tenantDefault` na cadeia de
> resolução. Isso une D2 e D3 num ponto só: `GovernanceSnapshot.llmPatch()`.

```java
// LLMSettings genérico = um LLMConfigPatch + chave por tenant
public class LLMSettings {
    private LLMConfigPatch patch = LLMConfigPatch.empty();  // provider/model/temp/maxTokens
    @JsonProperty(access = WRITE_ONLY) private String apiKey; // nunca serializa (padrão do gestor, linha 295)
    private int maxRetries = 3;
}
```

### 4.2 Profile, Snapshot e SPI de persistência

```java
// archflow-agent/.../governance/GovernanceProfile.java  (NOVO)
public record GovernanceProfile(
        String id, String tenantId, boolean active,
        GovernanceSettings settings,
        Instant createdAt, String createdBy, Instant updatedAt, String updatedBy
) {}

// archflow-agent/.../governance/GovernanceSnapshot.java  (NOVO)
public record GovernanceSnapshot(
        String profileId,        // unifica: integrall já tinha; gestor passa a ter
        String tenantId,
        GovernanceSettings settings,
        boolean fromDatabase,
        Instant resolvedAt
) {
    public LLMConfigPatch llmPatch() { return settings.getLlm().getPatch(); } // ponte p/ D2
}

// SPI — o PRODUTO implementa a persistência (JPA, etc.)
// archflow-agent/.../governance/GovernanceProfileStore.java  (NOVO)
public interface GovernanceProfileStore {
    Optional<GovernanceProfile> findActiveByTenant(String tenantId);
    Optional<GovernanceProfile> findActiveById(String profileId);
}
```

### 4.3 Resolver genérico

```java
// archflow-agent/.../governance/GovernanceResolver.java  (NOVO — contrato)
public interface GovernanceResolver {
    GovernanceSnapshot resolve(String tenantId);
    GovernanceSnapshot resolveByProfileId(String profileId);
    void invalidate(String tenantId);
    void invalidateAll();
}
```

`DefaultGovernanceResolver` (impl genérica) absorve o esqueleto comum e
**resolve por contrato as divergências**:

| Divergência | Decisão no supertipo |
|---|---|
| `null` vs defaults quando desligado | **sempre** retorna snapshot de defaults com `fromDatabase=false` (nunca `null`). |
| base64 hack | **fora** do core. Vira responsabilidade do `GovernanceProfileStore` do produto (se ainda precisar). |
| Caffeine vs ConcurrentHashMap | cache configurável; default Caffeine com TTL **respeitando** a property (corrige o bug do gestor). |
| `settingsJson` String vs objeto | core trabalha com `GovernanceSettings` (objeto); (de)serialização JSON fica no `GovernanceProfileStore`. |
| defaults hardcoded vs properties | core expõe `GovernanceSettings` default sobreponível; produto injeta os seus. |

## 5. Mapeamento por produto (depois de adotar o supertipo)

| Produto | Implementa | Reusa do archflow |
|---|---|---|
| `gestor-rq` | `GovernanceProfileStore` sobre `AgentGovernanceProfileJpaRepository`; mantém `AgentType` e seus agentes de tarefa | `GovernanceResolver`, `GovernanceSettings`, `GovernanceSnapshot`, ponte com `LLMConfigResolver` |
| `integrall` | `GovernanceProfileStore` sobre `AgentGovernanceProfileService`; mantém `ConversationContext`→profileId no adapter | idem |

Seções específicas (TenantIdentity, WhatsApp, Hallucination, Moderation
detalhada) vão para `extensions` ou viram libs opcionais — **não** poluem o núcleo.

## 6. O que fica de fora (harness)

Persistência concreta, `AgentType` de cada produto, regras de escalação de
negócio, `ConversationContext` específico, builders de contexto de domínio — tudo
permanece no produto, plugado via as SPIs (`GovernanceProfileStore`,
`TenantKeyResolver`).

## 7. Sequência sugerida

1. `GovernanceSettings` + seções genéricas (reusando `LLMConfigPatch` do D2).
2. `GovernanceProfile` + `GovernanceSnapshot` + `GovernanceProfileStore` (SPI).
3. `GovernanceResolver` + `DefaultGovernanceResolver` (esqueleto comum + decisões
   da tabela §4.3).
4. Adapter no `gestor-rq` e no `integrall` implementando o `GovernanceProfileStore`.
5. Plugar `GuardrailChain` (já existe no archflow) lendo `settings.guardrails`.

## 8. Status de implementação — IMPLEMENTADO

Passos 1, 2, 3 e 5 feitos em **`archflow-conversation`** (`.../conversation/governance/`).
Nota de localização: o módulo é `archflow-conversation` (não `archflow-agent` como
o rascunho tentava) — ele só depende de `archflow-model` (tem o `LLMConfigPatch`) e
co-localiza com `GuardrailChain`/`PromptRegistry`/`ConversationalAgent`, evitando as
deps pesadas do engine.

| Artefato | Status |
|---|---|
| `GovernanceSettings` + `LLMSettings` (reusa `LLMConfigPatch`, apiKey `WRITE_ONLY`) + `GuardrailSettings` + `RateLimitSettings` | ✅ |
| `GovernanceProfile`, `GovernanceSnapshot` (com `llmPatch()` — ponte p/ D2) | ✅ |
| `GovernanceProfileStore` (SPI, com `EMPTY`) | ✅ |
| `GovernanceResolver` + `DefaultGovernanceResolver` (cache TTL via ConcurrentHashMap; nunca null; ativo-only) | ✅ |
| Ponte `GovernanceGuardrails.from(settings)` → `GuardrailChain` (+ `PromptInjectionGuardrail`/`ForbiddenOutputGuardrail`, reusa `PiiRedactionGuardrail`) | ✅ |

**Decisões da §4.3 aplicadas:** nunca-null (defaults com `fromDatabase=false`); base64
fora do core (no store do produto); cache TTL respeitado; core trabalha com objeto
(JSON fica no store); defaults sobreponíveis.

**Follow-up (passo 4):** adapters de `GovernanceProfileStore` em gestor-rq/integrall;
e ligar `GovernanceSnapshot.llmPatch()` como `tenantDefault` no `LLMConfigResolver`.
