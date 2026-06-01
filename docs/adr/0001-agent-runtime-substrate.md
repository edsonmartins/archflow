# ADR-0001 — Agent Runtime Substrate: agente como primitivo, resolução de modelo escopável e governança versionada

- **Status:** Proposto
- **Data:** 2026-06-01
- **Decisores:** Edson Martins
- **Contexto de origem:** análise comparativa dos produtos `gestor-rq` e `integrall-commerce-api` (ambos construídos sobre LangChain4j), tratados como "laboratórios" do archflow.

## Sumário

Esta ADR consolida **três decisões P0** que, juntas, definem o que chamamos de
*Agent Runtime Substrate* do archflow — a camada por baixo da orquestração de
flows que dá suporte a agentes de IA reais em produção multi-tenant:

- **D1.** O agente passa a ser um **primitivo invocável diretamente**, não apenas
  um `StepType` dentro de um `Flow`.
- **D2.** A configuração de modelo (`model`, `apiKey`, `maxTokens`, `temperature`,
  …) torna-se **escopável por agente/step com herança de um default**, resolvida
  por uma cadeia explícita, com **resolução de chave por tenant**.
- **D3.** A configuração de governança é um **documento versionado (JSON)**, não um
  schema relacional normalizado.

## Contexto

O archflow é hoje **flow-first**: a unidade de execução é `Flow → FlowStep →
DefaultFlowEngine`, e a configuração de LLM existe apenas no nível do flow
(`FlowConfiguration.getLLMConfig()` →
`archflow-model/.../config/FlowConfiguration.java:29`). O `FlowStep`
(`archflow-model/.../flow/FlowStep.java:15`) **não carrega configuração de
modelo nenhuma** — todos os steps de um flow compartilham o mesmo `LLMConfig`
(`archflow-model/.../config/LLMConfig.java:8`), que é um record "cheio" (todos os
campos obrigatórios, sem noção de override parcial).

Dois produtos reais expuseram os limites desse desenho:

1. **`gestor-rq`** e **`integrall-commerce-api`** reimplementaram, de forma
   **independente e já divergente**, a mesma camada: resolução de modelo por
   tenant, governança por tenant, guardrails in/out, rate-limit por tenant e
   prompt versionado. Cada correção hoje precisa ser feita duas vezes.

2. **Nenhum dos dois usa o motor de flow.** Os agentes são beans Spring
   invocados diretamente por serviços de negócio (ex.: `TicketService` chama
   `TicketClassificationAgent`). Existem **dois formatos** de agente:
   - **Agente de tarefa** (single-shot, saída estruturada JSON) — `gestor-rq`
     (`AbstractGestorRqAgent`, com `AgentType`, `capabilities`, `keywords`,
     `requiredParameters`).
   - **Agente conversacional tool-calling** (loop multi-turn) —
     `integrall-commerce-api` (`LLMAgentOrchestratorImpl`, `ToolRegistry`).

3. **A dor recorrente** que motivou tudo: `maxTokens` e `model` compartilhados
   globalmente quebravam ou encareciam tarefas específicas (ex.: extração de
   preços por visão precisa de teto/modelo próprios). O `gestor-rq` resolveu isso
   com um `DynamicChatModelResolver` que aceita `maxOutputTokensOverride` e
   `modelOverride` por chamada
   (`gestor-rq-.../infrastructure/config/ai/DynamicChatModelResolver.java:65`).

4. **Schema-evolution é dolorosa.** O `gestor-rq` chegou a **desligar o Flyway**
   (`hbm2ddl.auto=update`, migrations viram só documentação — ver comentário em
   `V28__coleta_preco_auditoria_llm.sql`); o `integrall` mostra churn de migration
   (`V6 widen tipo_evento`, `V7/V8/V9` refatorando combo). **Ambos** escaparam
   dessa dor guardando a governança como **JSON blob** (`settingsJson` LONGTEXT),
   não como colunas — escolha deliberada para não migrar a cada novo campo.

### Fronteira de papéis (confirmada no código)

O que **sobe** para o archflow (orquestração genérica de IA): resolução de
modelo, governança, guardrails, rate-limit, prompt versionado, tool registry,
métricas de agente, transcrição/visão, JSON tolerante.

O que **fica nos produtos** (harness de negócio): de-para de catálogo
(Oracle/Consinco, Jaccard/pg_trgm), threshold de auto-aprovação, MAD/outliers,
agregação, roteamento por canal, fila de revisão, função Oracle
`api_calcular_precos_v3_fn`, regras de categoria/prioridade/SLA, cálculo fiscal.

## Decisão

### D1 — Agente como primitivo invocável

Introduzir um primitivo `Agent` em `archflow-agent` que pode ser **invocado
diretamente**, sem exigir a montagem de um `Flow`. O agente carrega um
**descriptor** (`agentType`, `capabilities`, `keywords`, `requiredParameters`,
`optionalParameters`, `description`) e é descoberto por um `AgentRegistry`. O
`Flow`/`FlowStep` passa a ser **um dos consumidores** do agente, não o único
caminho. Os dois formatos observados (tarefa single-shot e conversacional
tool-calling) são modelados como duas implementações sobre o mesmo contrato.

> Detalhamento técnico: `docs/design/0001-llm-config-resolver-and-agent-primitive.md`.

### D2 — `LLMConfig` mergeable + `LLMConfigResolver` + chave por tenant

1. Tornar a configuração de LLM **um patch parcial mergeable** (campos opcionais)
   em vez de um record "tudo-ou-nada". O valor efetivo é resolvido por uma
   **cadeia de herança explícita**:

   ```
   efetivo = step.override ?? agent.config ?? flow.config ?? tenant.default ?? platform.default
   ```

2. Introduzir um `LLMConfigResolver` que percorre essa cadeia e entrega um
   `LLMProviderConfig` final ao `LLMProviderHub`. Esse resolver é **também** o
   ponto de injeção da **chave por tenant** (via uma SPI `TenantKeyResolver`),
   porque "qual modelo agora?" e "qual chave agora?" são a mesma pergunta.

3. O `DynamicChatModelResolver` do `gestor-rq` é a **referência concreta** a
   generalizar (não desenhar no vácuo).

> Detalhamento técnico: `docs/design/0001-llm-config-resolver-and-agent-primitive.md`.

### D3 — Governança como documento versionado (JSON)

A configuração de governança por tenant é um **documento JSON versionado**
(`settingsVersion` monotônico), não um schema normalizado. O archflow define o
**contrato** (`GovernanceSettings`, `GovernanceResolver`, `GovernanceSnapshot`) e
deixa a **persistência para o produto** — exatamente como o `PromptRegistry`
(`archflow-conversation/.../prompt/PromptRegistry.java`) já faz para prompts. A
evolução de schema relacional explícita (Flyway, check constraints) fica reservada
ao que é genuinamente relacional: estado de flow e auditoria.

> Detalhamento técnico e extração do supertipo comum:
> `docs/design/0002-governance-service-convergence.md`.

## Consequências

### Positivas

- Elimina a duplicação divergente entre `gestor-rq` e `integrall-commerce-api`;
  uma correção de guardrail/provider passa a ser feita uma vez.
- Resolve a dor central (`maxTokens`/`model` por agente) tornando-a cidadã de
  primeira classe, com herança previsível.
- Permite portar agentes reais sem envelopá-los em flows artificiais.
- Governança como JSON evita migration a cada novo campo de config (lição
  aprendida nos dois produtos).
- A fronteira "framework vs harness" fica codificada em contratos, não em
  convenção verbal.

### Negativas / riscos

- `LLMConfig` mergeable é **breaking change** no `archflow-model` (campos passam a
  ser `Optional`/nuláveis). Exige caminho de compatibilidade — ver design D2.
- Mais um eixo de configuração (cadeia de herança) aumenta a superfície de
  testes; precisa de cobertura explícita da precedência.
- Governança como JSON troca segurança de schema por flexibilidade — mitigar com
  validação na borda (`@JsonIgnoreProperties(ignoreUnknown=true)` + defaults, como
  os dois produtos já fazem) e `settingsVersion`.

### Neutras

- Guardrails e prompt versionado **já existem** no archflow (`GuardrailChain`,
  `PromptRegistry`); o trabalho ali é **plugá-los na governança por tenant**, não
  reescrevê-los.

## Alternativas consideradas

1. **Manter flow-first e modelar todo agente como flow de 1 step.** Rejeitada:
   nenhum dos dois produtos reais opera assim; força cerimônia desnecessária e não
   resolve a herança de config de modelo.
2. **Config de modelo só por flow (status quo).** Rejeitada: é precisamente a dor
   que originou a análise.
3. **Governança como schema relacional normalizado.** Rejeitada: ambos os
   produtos fugiram disso por causa da dor de migration; JSON versionado é o
   padrão emergente comprovado.
4. **Unificar os dois produtos num único serviço.** Fora de escopo: eles já se
   comunicam por MCP (`GestorRqMcpClient`); o archflow só precisa dar o substrato
   comum.

## Plano de adoção (ordem)

1. D2 (resolver + config mergeable) — pré-requisito dos demais; extrair do
   `DynamicChatModelResolver`.
2. D1 (primitivo de agente) — ancora o resolver e o descriptor.
3. D3 (governança JSON) — supertipo comum extraído dos dois `AgentGovernanceService`.
4. Plugar `GuardrailChain` existente na governança; rate-limit por tenant.

## Referências

- `docs/design/0001-llm-config-resolver-and-agent-primitive.md`
- `docs/design/0002-governance-service-convergence.md`
- Referência concreta: `gestor-rq/.../ai/DynamicChatModelResolver.java`
- Referência concreta: `integrall-commerce-api/.../agents/core/agent/LLMAgentOrchestratorImpl.java`
