# Auditoria de capacidades do ArchFlow
Commit auditado: `5231c4a7ffb5c7826c3b7cffc94598ec195e581e` (branch `feat/vendax-mcp-integration`) | Data: 2026-07-25

> ## ⚠️ Adendo — correções aplicadas em 2026-07-25, DEPOIS da auditoria
>
> Este relatório é um retrato do commit `5231c4a`. Parte dos gaps que ele descreve foi corrigida
> logo em seguida, no diretório de trabalho. **O corpo do relatório não foi reescrito** — o valor
> dele é ser o retrato de antes. Use esta tabela para saber o que mudou:
>
> | ID | Veredito na auditoria | Situação agora | O que mudou |
> |----|----------------------|----------------|-------------|
> | C1 | `PARCIAL` | `NATIVO` (com ressalva) | Caminho MCP: `ToolAccessPolicy` obrigatória, aplicada ao montar o catálogo **e** antes de cada `callTool`. Caminho de workflow: `ComponentAccessPolicy` + `ScopedComponentCatalog` filtram no ponto de resolução, e o `ORCHESTRATE` herda o escopo. **Ressalva:** o escopo é *opt-in* — allowlist ausente ⇒ irrestrito, para não quebrar workflows salvos. O isolamento é agora possível e verificado; não é imposto por default. |
> | C2 | `AUSENTE` | `PARCIAL` | Resultado de tool carrega `ToolTrust`; conteúdo não-confiável volta ao modelo cercado (nonce por execução + regra no system prompt). Não atravessa `ComponentStep`/`ConversationalAgent`, que seguem devolvendo `Object`/`String` sem envelope. |
> | C3 | `PARCIAL` | `NATIVO` | **Dois** gates, ambos duráveis. No grafo: `StepType.APPROVAL` + `HumanApprovalStep` são o produtor que faltava, e `ApprovalQueueService` deriva a fila de `StateManager.findByStatus`. **No laço do agente** (o que faltava): `ToolApprovalPolicy` faz o laço suspender antes de executar a tool, persistir `McpAgentState` e retomar por `resume` — verificado com um runner novo retomando do estado, ou seja sobrevive a restart. `MemoryRestorer` ligado. Aberto: `executionPaths` persiste, mas nada reidrata um fluxo automaticamente no boot — a retomada é sempre disparada de fora. |
> | C4 | `AUSENTE` | `PARCIAL` | `ToolCatalogBudget` mede o custo do catálogo por execução, registra `tool_catalog_tokens` e avisa acima do limite nomeando os maiores contribuintes. **Seleção dinâmica continua ausente** — medir não é escolher, e nada é descartado. |
> | C6 | `PARCIAL` | `NATIVO` | `HttpMcpClient` fala Streamable HTTP completo: SSE, `Mcp-Session-Id`, recuperação de sessão vencida (404/400 → reinicializa e repete), `DELETE` no close. Verificado contra um server local que implementa a spec. Não abre o stream GET servidor→cliente (não recebe notificações não solicitadas). |
> | C5 | `PARCIAL` | `NATIVO` | `ToolInterceptorChain` ganhou chamador: `ComponentStep` invoca através dela e `beforeExecute` aborta de verdade. No laço MCP o veto é a `ToolAccessPolicy`. Fora: `ConversationalAgent` de `archflow-conversation` (inverteria dependência de módulo e é código sem chamador). |
> | C7 | `PARCIAL` | `PARCIAL` | Além do JSON malformado, argumentos válidos-mas-errados (obrigatório ausente, tipo trocado, fora do enum) são barrados contra o `inputSchema` e devolvidos ao modelo. Sem retry automático nem reparo de saída. |
> | C11 | `AUSENTE` | `PARCIAL` | Além do trace store e do coletor compartilhado: latência, contagem e taxa de falha **por nome de tool**, tokens por turno e custo do catálogo. Continua sem spans OTel. |
> | C12 | `PARCIAL` | `PARCIAL` (isolamento herdado) | Sub-agentes agora herdam o escopo de componentes do fluxo, então um supervisor restrito não delega para um agente irrestrito. O `ExecutionContext` continua sendo repassado inteiro. |
>
> | C10 | `PARCIAL` | `PARCIAL` (exercitado) | A memória de trabalho (conversa) agora sobrevive à suspensão via `FlowStateChatMemory`, sem tabela nova — vai nas variáveis do `FlowState`. A memória de **longo prazo** continua sem dono do lado do ArchFlow: `EpisodicMemory.store()` segue sem chamador, que é o comportamento desejado para o OpsLenz. |
>
> Inalterados: **C8, C9, C13**.
>
> ### Achado que a auditoria original não registrou — e sua correção
>
> **A camada de adapters LangChain4j (~25k LOC, 15 submódulos) não estava no caminho de execução.**
> O designer listava os providers e `createAdapter` não tinha chamador de produção algum: um nó
> apontando para "openai" falhava com "component not found", porque adapter não é `AIComponent`.
> Era o maior descompasso entre o que o projeto oferecia e o que executava.
>
> **Corrigido.** `LangChainAdapterComponent` faz a ponte (as duas interfaces são quase idênticas).
> Roteamento conservador — só entra no caminho de adapter quando o registry realmente tem aquele
> provider naquele tipo, então nenhum workflow existente muda. Criação preguiçosa (a factory exige
> chave), cache por tenant (a chave vem do `TenantKeyResolver`) e o resolver tem precedência sobre
> chave inline no JSON.
>
> **Efeito em cascata:** com adapters de chat executáveis, a memória de chat passou a ter escritor —
> e aí o `MemoryRestorer`, que eu tinha deixado desligado com justificativa, passou a se justificar.
> `FlowStateChatMemory` captura a conversa no checkpoint e a restaura no resume. Antes da ponte
> ligá-lo teria sido mais um hook sem chamador; depois dela, não ligá-lo deixava o agente amnésico
> ao voltar de um gate de aprovação.
>
> Coberto por `HumanApprovalGateE2ETest`, `ApprovalStepBeanGraphTest`, `McpAgentRunnerPolicyTest`,
> `UntrustedContentFenceTest`, `AgUiUntrustedInputTest`, `ComponentStepInterceptorTest`,
> `ScopedComponentCatalogTest`, `FlowComponentScopeTest`, `ApprovalQueueServiceTest`,
> `ToolArgumentValidatorTest`, `ToolCatalogBudgetTest`, `HttpMcpClientSpecInteropTest`,
> `LLMProviderHubConcurrencyTest` e os testes JDBC (H2 + Postgres real). Suíte do reator verde.
>
> **As duas respostas diretas do fim do relatório mudam assim:** (1) o interrupt/resume durável
> continua sendo de *step do grafo*, mas agora é **alcançável de fora** (step → fila → decisão →
> retomada); (2) o registro de tools continua global **por default**, mas passou a ser
> **escopável por fluxo e por agente, com a restrição aplicada na resolução** — nos dois caminhos.
>
> Incógnitas fechadas: interop MCP (server local que fala a spec), concorrência multi-tenant do
> `LLMProviderHub` (sem bug — o desenho se sustenta) e as migrations, que agora têm um teste que
> as aplica num Postgres limpo. **Ressalva:** esse teste e os demais de Postgres exigem Docker e
> foram escritos, não executados aqui — rodam no CI.
>
> **Resposta que muda para o ADR:** o ArchFlow passou a ter interrupt/resume durável também no
> nível do turno do agente. Um orquestrador externo deixa de ser requisito para esse caso; se
> entrar, entra como terceira implementação de `McpAgentStateStore`, guardando o mesmo
> `McpAgentState` — a troca é de custodiante, não de laço.

> Escopo entregue: C1–C13. C1–C5 foram investigadas a fundo (leitura de implementação + rastreio
> de chamadores em código de produção); C6–C13 foram verificadas com profundidade menor mas com
> evidência de código em todas. Regra aplicada: **um mecanismo que existe mas não tem chamador
> fora de teste é `PARCIAL`, nunca `NATIVO`** — e essa distinção domina o resultado desta auditoria.

---

## Veredito executivo

O ArchFlow **não é hoje um runtime agêntico**; é um **motor de workflow em grafo** (durável, com
checkpoint por step e retomada real) ao qual foram acopladas duas malhas de tool-calling
independentes e muito mais rasas. As primitivas que o OpsLenz precisa — allowlist de tools por
agente (`GovernanceProfile.isToolAllowed`), cadeia de interceptação com poder de veto
(`ToolInterceptorChain`), gate de aprovação durável (`DefaultFlowEngine.requestApproval/submitApproval`)
— **já existem implementadas e testadas, mas nenhuma tem chamador no caminho de execução real**.
São código morto de alta qualidade.

Na prática: o único laço de tool-calling que roda em produção
(`archflow-api/.../McpAgentRunner.java`) tem 155 linhas, envia todas as tools do MCP server ao
modelo a cada turno, executa `client.callTool(...)` sem nenhum ponto de interceptação e devolve o
resultado como texto puro para o contexto. O grafo de workflow, esse sim, tem durabilidade real —
mas seu catálogo de componentes é um `ConcurrentHashMap` global por processo, sem escopo por
agente ou tenant.

**O ArchFlow serve de runtime para o OpsLenz com esforço MÉDIO-ALTO**, e a maior parte desse
esforço é *fiação* (ligar mecanismos existentes ao laço real), não invenção. A exceção estrutural é
C3: a durabilidade do ArchFlow está no nível do **step do grafo**, não no nível do **turno do
agente** — se o gate de aprovação do OpsLenz precisar suspender *dentro* de um raciocínio
multi-turno com histórico de mensagens preservado, isso não existe e é trabalho GRANDE.

---

## Tabela de vereditos

| ID | Capacidade | Veredito | Esforço | Evidência principal |
|----|-----------|----------|---------|---------------------|
| C1 | Isolamento de tools por agente | `PARCIAL` | MÉDIO | `archflow-api/src/main/java/br/com/archflow/api/config/ArchflowBeanConfiguration.java:530` (catálogo global); `archflow-agent/src/main/java/br/com/archflow/agent/governance/GovernanceProfile.java:21` (allowlist sem chamador) |
| C2 | Proveniência / confiança do resultado de tool | `AUSENTE` | MÉDIO | `archflow-api/src/main/java/br/com/archflow/api/agent/mcp/McpAgentRunner.java:120`; `archflow-conversation/src/main/java/br/com/archflow/conversation/agent/ConversationTool.java:24` |
| C3 | Interrupção e retomada durável | `PARCIAL` | MÉDIO (grafo) / GRANDE (laço do agente) | `archflow-core/src/main/java/br/com/archflow/engine/core/DefaultFlowEngine.java:456-539`; `archflow-agent/src/main/java/br/com/archflow/agent/execution/DefaultFlowExecutor.java:210-216` |
| C4 | Seleção dinâmica de tools | `AUSENTE` | PEQUENO | `archflow-api/src/main/java/br/com/archflow/api/agent/mcp/McpAgentRunner.java:81,95` |
| C5 | Hook de política pré-invocação | `PARCIAL` | PEQUENO | `archflow-agent/src/main/java/br/com/archflow/agent/tool/ToolInterceptorChain.java:46-62` (implementado, aborta); zero chamadores fora de teste |
| C6 | Cliente MCP | `PARCIAL` | PEQUENO | `archflow-langchain4j/archflow-langchain4j-mcp/.../client/HttpMcpClient.java:25`; `.../client/StdioMcpClient.java` |
| C7 | Provider de LLM plugável | `PARCIAL` | PEQUENO | `.../provider/LLMProviderHub.java:326-338`; `.../provider/DefaultLLMConfigResolver.java:117-120` (baseUrl) |
| C8 | Mecanismo de extensão | `PARCIAL` | MÉDIO | `archflow-plugin-loader/.../ArchflowPluginManager.java` (sem chamador no servidor); `ArchflowBeanConfiguration.java:537-544` (lista hard-coded) |
| C9 | Definição declarativa de agente | `PARCIAL` | MÉDIO | `archflow-api/.../flow/DefaultFlowStepFactory.java:41-66`; `archflow-core/.../execution/ConditionEvaluator.java:33-37` |
| C10 | Memória: posse e delegação | `PARCIAL` | PEQUENO | `archflow-conversation/.../memory/EpisodicMemory.java` (zero chamadores de `store()`); `archflow-core/.../MemoryRestorer.java` |
| C11 | Observabilidade do próprio agente | `AUSENTE` | MÉDIO | `archflow-api/.../flow/FlowEngineFactory.java:92`; `ArchflowBeanConfiguration.java:184` (`MetricsCollector` = `null`) |
| C12 | Multi-agente | `PARCIAL` | MÉDIO | `archflow-agent/.../orchestration/DynamicSupervisor.java`; `.../orchestration/CatalogAgentWorker.java:42-47` |
| C13 | Testabilidade | `NATIVO` | — | `archflow-api/src/test/java/br/com/archflow/api/agent/qp/QpAgentIntegrationTest.java` |

---

## Ficha técnica

**Linguagem e versão.** Java 25 exclusivamente no backend (`pom.xml:16-18`:
`java.version`/`maven.compiler.source`/`target` = 25). Frontend em TypeScript/React.
Sem Kotlin, sem Groovy, sem `module-info.java` em nenhum módulo (verificado: `find . -name module-info.java` → vazio).

**Ordem de grandeza.**

| | Arquivos | LOC |
|---|---|---|
| Java (todos os módulos, exclui `target/`) | 930 | ~127.800 |
| TypeScript/TSX (`archflow-ui/src`) | 146 | ~22.600 |
| Testes (`*Test.java`) | 286 | — |

**Build.** Maven 3.8+ multi-módulo, 18 módulos no reator (`pom.xml:25-44`). BOMs importados:
Spring Boot 4.0.0, Apache Camel 4.3.0, LangChain4j 1.18.0, Testcontainers 1.20.4.

**Módulos (uma linha cada).**

| Módulo | LOC | O que faz |
|---|---|---|
| `archflow-model` | 6.2k | Domínio: `Flow`, `FlowStep`, `FlowState`, `ExecutionContext`, `AIComponent` |
| `archflow-core` | 6.7k | Motor: `DefaultFlowEngine`, `StateManager`, persistência JDBC, scheduler Quartz, primitivas de orquestração |
| `archflow-agent` | 19.2k | Executores de fluxo, métricas, streaming SSE, padrões de agente (ReAct/ReWOO/Plan-Execute), interceptores de tool, orquestração dinâmica |
| `archflow-api` | 22.8k | Servidor Spring Boot: REST, WebSocket/SSE, wiring de beans, AG-UI, runner MCP |
| `archflow-langchain4j` (15 submódulos) | 24.8k | Adapters SPI: providers de LLM, memória, vector stores, MCP, skills, RAG |
| `archflow-conversation` | 11.4k | Conversa: orquestrador, guardrails, personas, prompts, suspend/resume, memória episódica |
| `archflow-security` | 4.3k | JWT, RBAC, API keys, hashing de senha |
| `archflow-templates` | 4.9k | Templates de workflow registrados via SPI |
| `archflow-observability` | 6.3k | Classes OTel/Micrometer/auditoria (só auditoria é consumida) |
| `archflow-performance` | 5.4k | Cache de dois níveis — **módulo órfão, nenhum pom depende dele** |
| `archflow-plugins` | 3.9k | Plugins pré-construídos (agents/assistants/tools) |
| `archflow-marketplace` | 2.9k | Catálogo de manifests de extensão |
| `archflow-workflow-tool` | 2.4k | Workflow-as-Tool — **não é dependência do `archflow-api`** |
| `archflow-brainsentry` | 1.9k | Cliente Brain Sentry — **não é dependência do `archflow-api`** |
| `archflow-standalone` | 1.5k | Runner CLI + serialização YAML de flows |
| `archflow-plugin-api` | 1.3k | `ComponentCatalog`, `ComponentPlugin` (SPI), metadados |
| `archflow-events-proto` | 0.9k | Protocolo de eventos engine↔UI |
| `archflow-plugin-loader` | 0.8k | Carregamento de fat-jars com classloader child-first |
| `archflow-ui` | 22.6k TS | React 19 + Vite + Mantine + React Flow |

**Dependências de IA.** LangChain4j 1.18.0 é a única camada de IA (não há Spring AI, não há Embabel).
Não usa SDK MCP oficial — implementação **própria** de cliente e servidor MCP em
`archflow-langchain4j-mcp` (~1.750 LOC entre client e transport). Providers de LLM via
`langchain4j-{openai,anthropic,azure-open-ai,google-ai-gemini,bedrock,ollama,vertex-ai,watsonx,hugging-face}`.
Nada proprietário no núcleo.

**Framework base e forma.** Spring Boot 4.0.0. O runtime é **servidor**, não biblioteca embutível:
o `FlowEngine` é criado por uma factory sem Spring (`FlowEngineFactory` — "Kept as a plain factory
(no Spring) so the full graph is unit-testable without a context"), mas todo o resto
(catálogo, stores, controllers, MCP) é `@Bean` em `ArchflowBeanConfiguration`. Existe um caminho
CLI (`archflow-standalone/StandaloneRunner`) que roda um flow YAML sem servidor.

**Maturidade.** Último commit 2026-07-22. 244 commits totais, cadência concentrada:
53 em 2026-07, 89 em 2026-06, 40 em 2026-04, 41 em 2026-01, 16 em 2025-02. Uma única tag
versionada, `v1.0.0` (2026-03-13); versão do pom = `1.0.0`. CI em `.github/workflows/`
(`ci.yml`, `build-and-push.yml`, `docs.yml`, `release.yml`). **JaCoCo está configurado apenas com
`prepare-agent` + `report` + `report-aggregate` (`pom.xml:157-212`) — não há goal `check` nem
regra de cobertura mínima**, apesar de `CLAUDE.md` afirmar "minimum 80% required". A cobertura
real não é verificável sem rodar a suíte, e não há gate que a imponha.

**Estabilidade da API pública.** **Não há fronteira declarada entre API e interno.** Sem
`module-info.java`, sem anotação `@PublicApi`/`@Internal`, sem `package-info` marcando estabilidade
(o único `package-info.java` é documentação descritiva em `archflow-conversation`). Tudo é `public`
e alcançável. O `CHANGELOG.md` tem uma única entrada (1.0.0) e **descreve um projeto diferente do
que está no disco**: lista módulos que não existem (`archflow-langchain4j-streaming`,
`archflow-langchain4j-spring-ai`, `archflow-streaming`, `archflow-mcp` — verificado, nenhum existe)
e afirma "Dynamic plugin loading with Jeka", que o próprio javadoc do loader nega explicitamente
(`ArchflowPluginManager.java`: *"Versões antigas da documentação prometiam resolução dinâmica de
dependências via Jeka; isso nunca foi implementado"*). Não há histórico de breaking changes porque
não há histórico de releases — há um release só.

---

## Análise detalhada

### C1 — Isolamento de tools por agente

**Veredito:** `PARCIAL` · **Esforço:** MÉDIO

Existem **dois** universos de "tool" no ArchFlow, com propriedades de isolamento opostas.

#### Universo 1 — catálogo de componentes (caminho do workflow): registro **global**

O catálogo é um bean único de processo, com mapa plano `componentId → AIComponent` e nenhum
conceito de escopo, namespace ou tenant:

```java
// archflow-plugin-api/src/main/java/br/com/archflow/plugin/api/catalog/DefaultComponentCatalog.java:16-18
public class DefaultComponentCatalog implements ComponentCatalog {
    private final Map<String, AIComponent> components = new ConcurrentHashMap<>();
```

```java
// archflow-api/src/main/java/br/com/archflow/api/config/ArchflowBeanConfiguration.java:528-536
@Bean
@ConditionalOnMissingBean
public br.com.archflow.plugin.api.catalog.ComponentCatalog componentCatalog() {
    ...
    ComponentCatalog catalog = new DefaultComponentCatalog();
    String[] builtIns = { "br.com.archflow.plugins.agents.ConversationalAgent", ... };
```

A resolução `nome → executor` acontece em um único ponto, sem qualquer filtro:

```java
// archflow-api/src/main/java/br/com/archflow/api/flow/ComponentStep.java:51-53
AIComponent component = catalog.getComponent(componentId).orElse(null);
if (component == null) { ... "component not found: " + componentId ... }
```

E o `componentId` vem **direto do JSON do workflow**, com fallback para o campo `type` do nó:

```java
// archflow-api/src/main/java/br/com/archflow/api/flow/DefaultFlowStepFactory.java:52-66
Object componentId = node.get("componentId");
if (componentId == null) { componentId = node.get("type"); }
...
return new ComponentStep(id, StepType.TOOL, componentId.toString(), operation, connections, catalog);
```

Consequência direta: **qualquer workflow pode nomear qualquer componente registrado no processo.**
Um erro de digitação ou um workflow malicioso alcança tudo. Não há allowlist aplicada, não há
verificação de tenant no lookup.

#### Universo 2 — tools MCP (caminho do agente): isolamento **por instância**, sem granularidade

O laço real de agente resolve tools a partir de um `McpClient` que é por-tenant, não global:

```java
// archflow-api/src/main/java/br/com/archflow/api/mcp/vendax/VendaxMcpClientProvider.java:64-67
public McpClient clientFor(String tenantId) {
    return clients.computeIfAbsent(tenantId, this::buildAndConnect);
}
```

```java
// archflow-api/src/main/java/br/com/archflow/api/agent/mcp/McpAgentRunner.java:79-84
List<ToolSpecification> tools;
try {
    tools = McpToolSpecifications.from(client.listTools().get());
} catch (Exception e) { ... }
```

Aqui a isolação **entre agentes** sai de graça se cada agente receber um `McpClient` distinto —
mas a granularidade é o **server inteiro**: `listTools()` traz tudo o que o server expõe, sem filtro.
Dois agentes apontando para o mesmo MCP server enxergam exatamente o mesmo conjunto.

#### O mecanismo de allowlist existe — e não tem chamador

Esta é a descoberta mais relevante da capacidade. A estrutura de dados e a lógica de decisão
estão prontas:

```java
// archflow-agent/src/main/java/br/com/archflow/agent/governance/GovernanceProfile.java:21-25
public boolean isToolAllowed(String toolName) {
    if (disabledTools.contains(toolName)) return false;
    if (enabledTools.isEmpty()) return true;
    return enabledTools.contains(toolName);
}
```

`rg -n 'isToolAllowed' --type java` retorna **exatamente 6 ocorrências: a definição e 5 asserções
em `GovernanceProfileTest.java`**. Zero chamadores em código de produção. O mesmo vale para
`Persona.allowedTools()` (`archflow-conversation/.../persona/Persona.java:27`), cuja única
aplicação de verdade está **dentro de um teste**, no laço fake do próprio teste:

```java
// archflow-agent/src/test/java/br/com/archflow/agent/e2e/SacAgentE2ETest.java:441
if (!persona.allowedTools().contains(toolCall.name())) {
```

E a lista de tools habilitadas é exposta via REST — apenas para leitura, nunca aplicada:

```java
// archflow-api/src/main/java/br/com/archflow/api/workflow/impl/WorkflowConfigControllerImpl.java:168-169
List.copyOf(g.enabledTools()),
List.copyOf(g.disabledTools()),
```

**Análise.** O invariante do OpsLenz ("o agente que lê logs jamais alcança tools de escrita") é
**inaplicável hoje** no caminho de workflow (catálogo global, resolução por string livre) e
**aplicável por construção, mas grosseira** no caminho MCP (um server por agente). Não existe
allowlist *enforcada* em nenhum dos dois.

**Gap e esforço.** MÉDIO. Duas frentes: (a) chamar `isToolAllowed`/`allowedTools` no ponto de
resolução — trivial no `McpAgentRunner` (filtrar `tools` antes do `ChatRequest` e revalidar antes
do `callTool`), mais invasivo no `ComponentStep`, que precisaria receber um escopo que hoje não
existe no seu construtor; (b) introduzir o conceito de "agente" como portador desse escopo — o
ArchFlow tem `agentId` apenas como string opaca em `InvocationRequest`
(`archflow-agent/.../queue/InvocationRequest.java:21`), nunca como titular de configuração de
runtime.

---

### C2 — Proveniência e confiança do resultado de tool

**Veredito:** `AUSENTE` · **Esforço:** MÉDIO

Nos três laços que existem, o resultado de uma tool vira **texto colado no contexto**, sem envelope.

**No laço MCP (o que roda em produção):**

```java
// archflow-api/src/main/java/br/com/archflow/api/agent/mcp/McpAgentRunner.java:108-121
try {
    McpModel.ToolResult tr = client.callTool(new McpModel.ToolArguments(req.name(), args)).get();
    resultText = textOf(tr);
    isError = tr.isError();
} catch (Exception e) { ... }
toolCalls.add(new ToolCall(req.name(), args, resultText, isError));
messages.add(ToolExecutionResultMessage.from(req, resultText));
```

`ToolExecutionResultMessage.from(req, resultText)` é o tipo do LangChain4j — carrega `id` e `text`,
nada mais. Não há campo para origem, confiança ou classificação. `McpModel.ToolResult` chega a
ter estrutura (`content[]`, `isError`), mas `textOf()` a achata concatenando todos os `.text()`.

**No laço conversacional:** o contrato da tool *é* `String`:

```java
// archflow-conversation/src/main/java/br/com/archflow/conversation/agent/ConversationTool.java:15-24
@FunctionalInterface
public interface ConversationTool {
    String execute(Map<String, Object> params);
}
```

e o resultado é concatenado num `StringBuilder` que vira o prompt do turno seguinte:

```java
// archflow-conversation/src/main/java/br/com/archflow/conversation/agent/ConversationalAgent.java:104-107
transcript.append("\nAssistant: ").append(assistant)
        .append("\nToolResult(").append(tc.tool()).append("): ").append(toolResult);
```

**No caminho do grafo:** `AIComponent.execute` devolve `Object` cru e o step o grava em duas
variáveis de contexto sem qualquer marcação:

```java
// archflow-api/src/main/java/br/com/archflow/api/flow/ComponentStep.java:56-59
Object output = component.execute(operation, input, context);
context.set(id, output);
context.set(INPUT_KEY, output);
```

#### Existe um envelope — no ramo morto

`ToolResult<T>` (`archflow-agent/src/main/java/br/com/archflow/agent/tool/ToolResult.java:12-27`)
tem exatamente a forma necessária: `data`, `status`, `message`, `error`, `timestamp` e
**`Map<String, Object> metadata`**. Seria o lugar natural para `trusted|untrusted`. Só que essa
classe pertence ao subsistema `InterceptableToolExecutor`, que não é usado por nenhum laço real
(ver C5). O tipo `br.com.archflow.model.ai.domain.Result` (usado pelo catálogo) também tem
`Map<String, Object> metadata` — e o `ComponentStep` **descarta** o `Result` inteiro, pegando só
`output`.

**Os guardrails não cobrem tool output.** A cadeia de guardrails só tem dois pontos de avaliação,
e nenhum deles é o retorno de tool:

```java
// archflow-conversation/src/main/java/br/com/archflow/conversation/guardrail/AgentGuardrail.java:35,45
default GuardrailResult evaluateInput(String message, Map<String, Object> context)
default GuardrailResult evaluateOutput(String response, Map<String, Object> context)
```

Existe um `PromptInjectionGuardrail` (`.../guardrail/builtin/PromptInjectionGuardrail.java`), mas
ele roda sobre a **mensagem do usuário**, não sobre linhas de log devolvidas por uma tool.

**Análise.** Para o OpsLenz isto é o gap mais silenciosamente perigoso: uma linha de log com
`"IGNORE PREVIOUS INSTRUCTIONS"` entra no transcript com exatamente o mesmo status epistêmico da
instrução do operador. Não há nem marcação, nem ponto onde marcar.

**Gap e esforço.** MÉDIO. O ponto de interceptação onde anexar proveniência não existe no laço real —
precisa ser criado junto com C5. Depois disso, "o runtime respeitar" a marcação é trabalho adicional:
`ToolExecutionResultMessage` do LangChain4j não tem campo para isso, então ou se delimita o conteúdo
no texto (frágil, mas barato) ou se troca o tipo de mensagem por um wrapper próprio (caro, afeta a
integração com o `ChatModel`).

---

### C3 — Interrupção e retomada durável

**Veredito:** `PARCIAL` · **Esforço:** MÉDIO (nível de grafo) / GRANDE (nível de laço de agente)

Esta é a capacidade em que o ArchFlow tem mais substância — e onde a distinção pedida no prompt
(pausa em memória / persistência de histórico / retomada durável de execução) separa três coisas
que aqui realmente são diferentes.

#### O que É retomada durável de execução, e funciona

O `DefaultFlowEngine` implementa suspensão cooperativa com estado persistido. O commit mais recente
do repositório (`5231c4a`) é precisamente a correção desse caminho:

```java
// archflow-core/src/main/java/br/com/archflow/engine/core/DefaultFlowEngine.java:418-452
public String requestApproval(String flowId, String stepId, Object proposal) {
    synchronized (transitionLock) {
        ...
        String requestId = java.util.UUID.randomUUID().toString();
        execution.getContext().set(APPROVAL_REQUEST_KEY, requestId);
        syncVariablesToState(execution.getContext());
        ...
        FlowState awaitingState = FlowState.builder()...status(FlowStatus.AWAITING_APPROVAL)...build();
        execution.getContext().setState(awaitingState);
        stateManager.saveState(flowId, awaitingState);
        executionManager.pauseFlow(flowId);   // desenrola a travessia de fato
```

e a retomada opera **a partir do estado persistido**, explicitamente projetada para o caso em que o
processo não tem mais nada em memória:

```java
// archflow-core/src/main/java/br/com/archflow/engine/core/DefaultFlowEngine.java:466-475
// A suspensão cooperativa (AWAITING_APPROVAL) desenrola a travessia
// e remove o fluxo de activeExecutions — então, quando o humano
// decide, o fluxo normalmente NÃO está mais vivo em memória. A fonte
// de verdade é o estado persistido; a entrada viva, quando existe
// (ex.: mock que bloqueia em testes), é apenas um atalho.
FlowExecution execution = activeExecutions.get(flowId);
ExecutionContext liveContext = execution != null ? execution.getContext() : null;
FlowState currentState = liveContext != null ? liveContext.getState() : stateManager.loadState(flowId);
```

```java
// archflow-core/src/main/java/br/com/archflow/engine/core/DefaultFlowEngine.java:532-537
} else {
    // Fluxo reidratado do store: contexto novo; resumeFlow recarrega
    // estado e variáveis a partir do StateManager.
    contextForResume = createResumeContext(consumedState);
}
return resumeFlow(flowId, contextForResume);
```

O checkpoint é por step, não só nas transições:

```java
// archflow-api/src/main/java/br/com/archflow/api/flow/CheckpointingLifecycleListener.java:34-47
@Override public void onStepCompleted(...) { checkpoint(flow, context); }
@Override public void onStepFailed(...)    { checkpoint(flow, context); }
@Override public void onStepSkipped(...)   { checkpoint(flow, context); }
```

e o resume **não reexecuta** o que já rodou:

```java
// archflow-agent/src/main/java/br/com/archflow/agent/execution/DefaultFlowExecutor.java:207-216
// Resume incremental: step concluído em execução anterior não
// reexecuta (efeitos colaterais não duplicam); apenas propaga
// pelos alvos — os outputs restaurados alimentam as condições.
if (completedInPriorRun.contains(step.getId())) {
    logger.info("Step " + step.getId() + " já concluído em execução anterior; pulando (resume)");
```

O backend durável existe e é ligado por propriedade:

```java
// archflow-api/src/main/java/br/com/archflow/api/config/JdbcPersistenceConfiguration.java:56-59
@Bean public StateManager stateManager(DataSource dataSource) {
    return new RepositoryStateManager(new JdbcStateRepository(dataSource));
}
```

com upsert em `flow_states` (`archflow-core/.../jdbc/JdbcStateRepository.java:73-83`).
Há endpoint REST para disparar a retomada (`SpringExecutionController.java:90-116`).
Cobertura: `DefaultFlowEngineApprovalDurabilityTest` (6 casos, adicionado em `5231c4a`).

**Portanto: sim, sobrevive a restart de processo — no nível do grafo.**

#### O que exatamente é persistido (e o que não é)

Persistido em `flow_states`: `tenant_id`, `flow_id`, `status`, `current_step_id`,
`variables` (JSON), `metrics` (JSON), `error` (JSON). As `variables` incluem os outputs de cada step
e a lista `__archflow.completedSteps`.

**Não persistido:**
- `executionPaths` — o próprio código admite: *"executionPaths não tem coluna no schema atual (V1) e
  segue não-persistido"* (`JdbcStateRepository.java:172-175`);
- **o histórico de mensagens do LLM.** `ExecutionContext.getChatMemory()` existe
  (`archflow-model/.../ExecutionContext.java:49`) mas nada o serializa. Há um SPI para isso —
  `MemoryRestorer` (`archflow-core/.../MemoryRestorer.java`) — e ele é passado como **`null`**:
  ```java
  // archflow-api/src/main/java/br/com/archflow/api/flow/FlowEngineFactory.java:91-92
  null,   // memoryRestorer — tolerated null
  null,   // traceRecorder — tolerated null
  ```
  O endpoint de resume constrói uma memória **vazia**:
  ```java
  // archflow-api/src/main/java/br/com/archflow/api/web/workflow/SpringExecutionController.java:105-107
  ExecutionContext ctx = new DefaultExecutionContext(
          state.getTenantId(), "runner", id,
          MessageWindowChatMemory.builder().maxMessages(20).build());
  ```
- **tool calls pendentes de um laço de agente.** O `McpAgentRunner` mantém `List<ChatMessage> messages`
  em heap (`McpAgentRunner.java:86-90`) e não tem nenhum ponto de checkpoint. Se o processo cair no
  meio de um raciocínio de 8 turnos, tudo se perde.

#### O gate de aprovação está implementado e **não tem produtor nem endpoint**

`rg -n 'requestApproval|submitApproval' --type java` retorna 4 arquivos: a interface `FlowEngine`,
a implementação `DefaultFlowEngine` e **dois arquivos de teste**. Nenhum controller REST chama;
nenhum `StepType` dispara. O enum `StepType` não tem valor de aprovação humana:

```java
// archflow-model/src/main/java/br/com/archflow/model/flow/StepType.java
public enum StepType { ASSISTANT, AGENT, TOOL, CHAIN, CUSTOM, ORCHESTRATE }
```

Em paralelo, existe uma **segunda** malha de aprovação, exposta via REST — e desconectada do motor:

```java
// archflow-api/src/main/java/br/com/archflow/api/config/ArchflowBeanConfiguration.java:285
return new ApprovalQueueService(new br.com.archflow.conversation.approval.ApprovalRegistry());
```

`ApprovalRegistry` é um par de `ConcurrentHashMap` em memória
(`archflow-conversation/.../approval/ApprovalRegistry.java:28-29`), e **nada chama seu `register()`**.
Ou seja: os endpoints `GET /api/approvals/pending` e `POST /api/approvals/{id}`
(`SpringApprovalController.java:21-38`) servem uma fila que nunca é populada e que, mesmo se fosse,
se perderia no restart.

#### O terceiro mecanismo: suspend/resume de conversa é um estacionamento, não uma retomada

`ConversationManager.resume()` **não continua execução nenhuma** — troca o status do registro e
devolve:

```java
// archflow-conversation/src/main/java/br/com/archflow/conversation/ConversationManager.java:170-179
SuspendedConversation resumed = suspended.resume(formData != null ? formData : Map.of());
store.save(resumed);
log.info("Resumed conversation {} with token {}", ...);
publishEvent(ArchflowEvent.resume(suspended.getConversationId(), formData));
return Optional.of(resumed);
```

Isso é persistência durável de um formulário + token (`JdbcSuspendedConversationStore`), não
retomada de execução.

**Análise para o OpsLenz.** O gate "propor remediação → aprovação humana → executar" **cabe** se ele
for modelado como uma **aresta do grafo** (step N propõe, motor suspende em `AWAITING_APPROVAL`,
humano decide, step N+1 executa). Nesse formato, o ArchFlow já entrega durabilidade real com
retomada incremental. **Não cabe** se o gate precisar acontecer *dentro* de um turno de raciocínio
do agente, com o histórico de mensagens preservado — porque o laço do agente não tem checkpoint
nem serialização de estado.

**Gap e esforço.** MÉDIO para o caminho de grafo: falta apenas o produtor (um step ou um endpoint que
chame `requestApproval`) e um endpoint que chame `submitApproval` — o núcleo difícil está feito e
testado. GRANDE para o caminho de agente: exigiria transformar `McpAgentRunner` em uma máquina de
estados persistível (serializar `List<ChatMessage>`, tool calls pendentes, iteração corrente), que
é essencialmente reimplementar o que Temporal dá pronto.

---

### C4 — Seleção dinâmica de tools

**Veredito:** `AUSENTE` · **Esforço:** PEQUENO

O catálogo inteiro vai para o prompt, a cada turno, sem filtro e sem contabilidade:

```java
// archflow-api/src/main/java/br/com/archflow/api/agent/mcp/McpAgentRunner.java:79-96
List<ToolSpecification> tools;
try {
    tools = McpToolSpecifications.from(client.listTools().get());
} catch (Exception e) { ... }
...
for (int i = 1; i <= maxIterations; i++) {
    ChatResponse response = model.chat(ChatRequest.builder()
            .messages(messages)
            .toolSpecifications(tools)      // ← a lista completa, todo turno
            .build());
```

`McpToolSpecifications.from(...)` é uma conversão 1:1 sem seletividade:

```java
// archflow-api/src/main/java/br/com/archflow/api/agent/mcp/McpToolSpecifications.java:30-38
public static List<ToolSpecification> from(List<McpModel.Tool> tools) {
    List<ToolSpecification> specs = new ArrayList<>(tools.size());
    for (McpModel.Tool tool : tools) {
        specs.add(ToolSpecification.builder()...build());
    }
    return specs;
}
```

No caminho conversacional a serialização é um bloco de texto, também completo:

```java
// archflow-conversation/src/main/java/br/com/archflow/conversation/agent/DefaultToolRegistry.java:52-61
public String describe() {
    Map<String, String> sorted = new LinkedHashMap<>();
    descriptions.keySet().stream().sorted().forEach(k -> sorted.put(k, descriptions.get(k)));
    StringBuilder sb = new StringBuilder();
    sorted.forEach((name, desc) -> sb.append("- ").append(name).append(": ").append(desc).append('\n'));
```

**Não há:** filtro, busca semântica sobre tools, carregamento lazy, agrupamento por toolset, limite
configurável de tools no prompt, nem qualquer contabilização de tokens do catálogo. Busquei por
`estimateTokens`, `TokenUsage`, `totalTokenCount` — as ocorrências em código de produção estão em
`orchestration/Budget.java`, `BudgetLedger.java` e `Usage.java`, que contabilizam tokens **reportados
pelos agentes** no orquestrador dinâmico, nunca o custo do catálogo.

O que **existe** e poderia ser confundido com seleção dinâmica:

- `ComponentQueryRouter` (`archflow-plugin-api/.../DefaultComponentQueryRouter.java`) — roteia uma
  query para **um componente/agente** (keywords > capabilities > tags > texto). É seleção de agente,
  não de tool, e opera sobre o catálogo global.
- `SemanticRouter` (`archflow-agent/.../routing/SemanticRouter.java`) — roteia queries para
  *handlers* por similaridade de embedding. **Sem chamador em produção** (`rg` retorna só a classe e
  seu teste). Também não é seleção de tool.

**Análise.** Com 10 capability packs, o catálogo compete linearmente com o incidente pelo espaço da
janela, e o ArchFlow não oferece nem visibilidade (quantos tokens) nem controle (quais enviar).

**Gap e esforço.** PEQUENO. O ponto de injeção é uma linha
(`McpAgentRunner.java:81` → filtrar a lista antes do laço, ou por turno). O trabalho real é decidir
a política de seleção, não o encaixe.

---

### C5 — Hook de política pré-invocação

**Veredito:** `PARCIAL` · **Esforço:** PEQUENO

**O mecanismo está completo, correto e testado.** A interface tem `beforeExecute` que lança para
impedir a execução:

```java
// archflow-agent/src/main/java/br/com/archflow/agent/tool/ToolInterceptor.java:39-41
/** @throws ToolInterceptorException Se a execução não deve prosseguir */
default void beforeExecute(ToolContext context) throws ToolInterceptorException { }
```

E a cadeia honra o veto, abortando **antes** de tocar a tool:

```java
// archflow-agent/src/main/java/br/com/archflow/agent/tool/ToolInterceptorChain.java:45-62
// Fase 1: Before execute
for (ToolInterceptor interceptor : interceptors) {
    try {
        interceptor.beforeExecute(context);
    } catch (ToolInterceptorException e) {
        if (e.shouldAbort()) {
            log.warn("[{}] Interceptor {} abortou execução: {}", ...);
            return handleInterceptorError(context, interceptor, e);   // ← nunca chega na Fase 2
        }
    } catch (Exception e) { ... return handleInterceptorError(...); }
}
// Fase 2: Execute tool
result = toolExecutor.execute(context);
```

Há ordenação determinística (`ToolInterceptor.order()`, `ToolInterceptorChain.java:28`) e o contexto
entregue ao hook é suficiente para decidir: `toolName`, `input`, e o `ExecutionContext` completo com
`getTenantId()`, `getUserId()`, `getSessionId()`, `getRequestId()`
(`archflow-model/.../ExecutionContext.java:74-96`). Existem quatro implementações prontas
(`LoggingInterceptor`, `MetricsInterceptor`, `CachingInterceptor`, `GuardrailsInterceptor`) e uma
quinta em `archflow-brainsentry/.../BrainSentryInterceptor.java`.

**E nada disso roda.** `rg -n 'InterceptableToolExecutor|ToolInterceptorChain|ToolInterceptor\b'
--type java --glob '!*Test*'` retorna **exclusivamente** os próprios arquivos do subsistema e as
implementações de interceptor. Nenhum laço de agente, nenhum step, nenhum bean Spring o instancia.
O `McpAgentRunner` chama `client.callTool(...)` diretamente (`McpAgentRunner.java:110-111`); o
`ConversationalAgent` chama `tool.get().execute(tc.params())` diretamente
(`ConversationalAgent.java:122`); o `ComponentStep` chama `component.execute(...)` diretamente
(`ComponentStep.java:56`).

O javadoc do próprio `McpAgentRunner` reconhece que ele é uma peça nova, escrita ao lado do que já
existia: *"Loop de tool-calling NATIVO server-side — o motor que faltava no ArchFlow"*
(`McpAgentRunner.java:26-27`).

**Análise.** Cedar/OPA teria onde encaixar — a interface é adequada, recebe identidade e argumentos,
pode negar, e o resultado do veto é um `ToolResult` de erro (auditável em princípio, embora hoje o
único registro seja o `log.warn`). O problema é que o ponto de encaixe está desconectado do caminho
de execução.

**Gap e esforço.** PEQUENO — para o caminho MCP. É envolver a chamada em `McpAgentRunner.java:110`
com a cadeia (ou reescrever o `ToolExecutor` funcional para delegar a ela). MÉDIO se for necessário
cobrir também `ComponentStep` e o laço conversacional, porque aí são três pontos de invocação
distintos com três tipos de retorno distintos — e nenhum contrato comum entre eles.

---

## Capacidades secundárias

### C6 — Cliente MCP · `PARCIAL` · PEQUENO

Implementação **própria** (não usa o SDK MCP oficial), em `archflow-langchain4j-mcp`.

**Transportes.** Dois:
- **HTTP** — `HttpMcpClient` (266 LOC). O javadoc é explícito sobre o escopo:
  *"MCP client sobre HTTP (subset 'Streamable HTTP' **sem SSE**)"* (`HttpMcpClient.java:25`).
  Cada operação é um POST JSON-RPC 2.0 isolado; `connect()` é um no-op que só marca a flag
  (`HttpMcpClient.java:72-76`). Não há session id (`Mcp-Session-Id`), não há stream de servidor.
- **stdio** — `StdioMcpClient` (615 LOC) + `StdioClientTransport` (415 LOC), com política de
  segurança para subprocessos: allowlist de executáveis e bloqueio de env vars perigosas
  (`McpCommandPolicy.java:35-51`, default `npx, node, python, python3, uvx, uv, docker, java`).

**SSE: não existe.** Nenhum transporte SSE de cliente.

**Reconexão: não existe.** `rg -i 'reconnect|backoff'` sobre o módulo MCP retorna zero ocorrências
relevantes. `HttpMcpClient.close()` só zera um `AtomicBoolean` (`HttpMcpClient.java:116-118`).

**Falha de servidor MCP.** Tratada como exceção pontual, sem recuperação: falha no `listTools()`
aborta o run inteiro (`McpAgentRunner.java:80-84`); falha num `callTool()` vira texto de erro
devolvido ao modelo (`McpAgentRunner.java:114-118`). O `VendaxMcpClientProvider` cacheia clients por
tenant indefinidamente — só invalida em `configure()` (`VendaxMcpClientProvider.java:46-52`); um
server que morra deixa um client cacheado apontando para o vazio.

Há também um lado **servidor** MCP (`AbstractMcpServer`, `WorkflowMcpServer`, `MemoryMcpServer`),
fora do escopo desta pergunta.

### C7 — Provider de LLM plugável · `PARCIAL` · PEQUENO

**A metade da pluggabilidade é `NATIVO`.** `LLMProviderHub` cobre 16 providers com `switch`
exaustivo (`LLMProviderHub.java:282-297`), e o `baseUrl` é sobrescrevível ponta a ponta:

```java
// .../provider/DefaultLLMConfigResolver.java:117-120
Object baseUrl = resolved.additionalConfig().get("baseUrl");
if (baseUrl != null) { builder.baseUrl(baseUrl.toString()); }
```

```java
// .../provider/LLMProviderHub.java:332-334
static String effectiveBaseUrl(LLMProviderConfig config) {
    return config.getBaseUrl() != null ? config.getBaseUrl() : config.getProvider().getBaseUrl();
}
```

Apontar `provider=openai` + `additionalConfig.baseUrl=http://vllm:8000/v1` funciona; `OLLAMA` é
provider de primeira classe. Não há amarração a nenhum vendor. A resolução é hierárquica
(plataforma < tenant < flow < agente < step, `DefaultLLMConfigResolver.java:40-46`) com chave de API
por tenant.

**A metade de robustez é `AUSENTE`.** Para modelos com tool-calling fraco não há retry, não há
validação de schema da chamada, não há reparo de saída malformada. Argumento não-JSON é
**silenciosamente descartado**:

```java
// archflow-api/src/main/java/br/com/archflow/api/agent/mcp/McpAgentRunner.java:132-139
try {
    Map<String, Object> parsed = mapper.readValue(argumentsJson, Map.class);
    return parsed != null ? parsed : Map.of();
} catch (Exception e) {
    log.warn("Argumentos de tool não-JSON, usando vazio: {}", argumentsJson);
    return Map.of();      // ← a tool é chamada com argumentos vazios
}
```

Existe um executor com validação de schema e retry estrito — `FuncAgentExecutor` +
`StrictRetryPolicy` + `OutputSchema` (`archflow-agent/.../deterministic/`) — mas seu único
consumidor é `archflow-workflow-tool/.../WorkflowTool.java`, e **`archflow-workflow-tool` não é
dependência do `archflow-api`** (verificado em `archflow-api/pom.xml`). Fora do servidor.

**Ressalva de baixa confiança:** o `LLMProviderHub` é singleton estático
(`LLMProviderHub.getInstance()`, `LLMProviderHub.java:98-107`) com cache de modelos por processo.
Não investiguei o comportamento sob troca concorrente de config entre tenants além de notar o uso de
`ThreadLocal` para overrides temporários (`LLMProviderHub.java:85`).

### C8 — Mecanismo de extensão · `PARCIAL` · MÉDIO

**No papel, três mecanismos. Na prática, um.**

1. **`ServiceLoader` para adapters LangChain4j** — funciona, é o mecanismo real de extensão de
   providers: `LangChainRegistry` carrega `LangChainAdapterFactory` via SPI com idioma
   *initialization-on-demand holder* (`LangChainRegistry.java:35-45`). Descoberta em runtime, sim,
   mas restrita a adapters, e o registro é **estático global**.

2. **`ServiceLoader` + diretório de plugins fat-jar** — `ArchflowPluginManager` existe, com
   classloader child-first documentado. **Não é usado pelo servidor**: `archflow-plugin-loader` não
   está no `pom.xml` do `archflow-api`; o único consumidor é `ArchFlowAgent`
   (`archflow-agent/.../ArchFlowAgent.java:81`), que por sua vez **nunca é instanciado** pelo
   `archflow-api` (só aparece em javadoc). O javadoc do próprio manager avisa: sem sandbox, sem
   resolução de dependências, `onLoad` roda código arbitrário com os privilégios da JVM.

3. **Lista hard-coded por reflexão** — é o que o servidor realmente faz:
   ```java
   // archflow-api/src/main/java/br/com/archflow/api/config/ArchflowBeanConfiguration.java:537-553
   String[] builtIns = {
           "br.com.archflow.plugins.agents.ConversationalAgent",
           "br.com.archflow.plugins.agents.ResearchAgent", ... };
   for (String className : builtIns) {
       Class<?> cls = Class.forName(className);
       Object instance = cls.getDeclaredConstructor().newInstance();
       if (instance instanceof AIComponent aic) { catalog.register(aic); }
   ```

O mecanismo de extensão **efetivo** para adicionar capacidade sem recompilar o núcleo é o Spring:
praticamente todo bean é `@ConditionalOnMissingBean`, então um deployment substitui qualquer peça
declarando o seu. Isso é wiring de tempo de compilação do *deployment*, não descoberta em runtime.

Há um quarto caminho, de granularidade menor: `SkillsManager` + `FileSystemSkillLoader` carregam
"skills" de um diretório configurável (`ArchflowBeanConfiguration.java:597-611`), com falha
silenciosa se o diretório não existir.

### C9 — Definição declarativa de agente · `PARCIAL` · MÉDIO

**Workflow: declarativo.** Um workflow é JSON (nós `{id, type, componentId, config, connections}`)
desserializado em `FlowStep` executável por `DefaultFlowStepFactory.create(Map<String,Object>)`
(`DefaultFlowStepFactory.java:41-66`), e há ponte para YAML
(`archflow-api/.../workflow/WorkflowYamlBridge.java`, reusando `archflow-standalone/YamlFlowSerializer`).

**Expressividade de lógica condicional: baixa mas real.** A gramática é fechada e documentada:

```java
// archflow-core/src/main/java/br/com/archflow/engine/execution/ConditionEvaluator.java:33-37
private static final Pattern COMPARISON = Pattern.compile("^(.+?)\\s*(==|!=|>=|<=|>|<)\\s*(.+)$");
private static final Pattern CONTAINS   = Pattern.compile("^(.+?)\\s+contains\\s+(.+)$", CASE_INSENSITIVE);
private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{(.+)}$");
```

`${var} op literal`, mais `contains`, mais operando único por veracidade. Sem booleanos compostos
(`&&`/`||`), sem aritmética, sem chamada de função. Caminhos de erro são declaráveis
(`isErrorPath`/`errorPath` nas conexões, `DefaultFlowStepFactory.java:96-97`). Condição não avaliável
**segue a transição** com warning (comportamento permissivo, documentado em
`ConditionEvaluator.java:26-28`) — o que é uma armadilha para uma política de segurança expressa
como condição.

**Agente: não declarativo.** Não existe formato de arquivo, anotação ou schema que descreva um agente
(prompt + tools + política + modelo). O que mais se aproxima é `GovernanceProfile`
(`archflow-agent/.../governance/GovernanceProfile.java:5-6`: `systemPrompt`, `enabledTools`,
`disabledTools`, `maxToolExecutions`, `escalationThreshold`) — um record com REST de leitura e
**nenhuma aplicação em runtime** (ver C1). Um "agente" no ArchFlow é ou uma classe Java implementando
`AIAgent`, ou uma composição imperativa como `QpAgentService`.

### C10 — Memória: posse e delegação · `PARCIAL` · PEQUENO

**A resposta curta é favorável ao OpsLenz: o ArchFlow aceita não ser dono da memória, porque hoje
ele não escreve memória de longo prazo nenhuma.**

**Separação entre estado de trabalho e memória de longo prazo: existe e é limpa.**
- Estado de trabalho = `FlowState.variables` (JSON no `flow_states`) + `ExecutionContext.getChatMemory()`
  (`ChatMemory` do LangChain4j, in-heap, não serializado).
- Memória de longo prazo = interface `EpisodicMemory`
  (`archflow-conversation/.../memory/EpisodicMemory.java`), com implementações
  `InMemoryEpisodicMemory` e `BrainSentryMemoryAdapter`.

São conceitos distintos, em módulos distintos, sem acoplamento.

**Abstração plugável: existe.** `EpisodicMemory` é interface pura; o `BrainSentryMemoryAdapter`
prova que um backend externo encaixa (`BrainSentryMemoryAdapter.java:25`, mapeando `store()` →
`POST /v1/memories`, `recall()` → busca híbrida).

**Gravação automática pelo laço: não existe.** `rg -n '\.store\(' --type java` (excluindo testes)
retorna **zero** chamadores. Nenhum laço de agente, nenhum step, nenhum orquestrador escreve em
`EpisodicMemory`. Não há efeito colateral a desligar.

**Persistência paralela: só de conversa, não de memória.** `ConversationOrchestrator` grava toda
mensagem no `ConversationRepository` (`ConversationOrchestrator.java:107,116`) — histórico de
conversa, com propósito de auditoria (*"audit log, keeps full history regardless of memory window"*).
Isso é ortogonal a um grafo de memória externo e não duplica sua fonte de verdade. Além disso, o
`ConversationOrchestrator` não é instanciado por nenhum bean do `archflow-api`.

**A ressalva.** O `archflow-brainsentry` **não está no classpath do servidor** (não consta em
`archflow-api/pom.xml`). O que existe no `archflow-api` é apenas
`br/com/archflow/api/brainsentry/BrainSentryConfigController` — armazena configuração, sem nenhum
`import br.com.archflow.brainsentry`. Ou seja: a delegação de memória é *possível por desenho*
e **nunca foi exercitada em runtime**. Verdicto `PARCIAL` por isso, não por obstáculo estrutural.

**Onde ficaria o ponto de controle:** o único gancho de leitura ligado ao motor é `MemoryProvider`
(`archflow-agent/.../memory/MemoryProvider.java`, injeta contexto no início da execução) e
`MemoryRestorer` (`archflow-core/.../MemoryRestorer.java`, restaura no resume) — ambos passados como
`null` no wiring atual (`FlowEngineFactory.java:91`). Para escrita, não há gancho: teria que ser
criado, o que é bom para o gate de segurança (nada escreve sem que você escreva o código).

### C11 — Observabilidade do próprio agente · `AUSENTE` · MÉDIO

**OpenTelemetry: as classes existem, nada instrumenta.** `archflow-observability` declara
`opentelemetry-api/sdk/sdk-trace/exporter-otlp` 1.42.0 e Micrometer 1.13.4
(`archflow-observability/pom.xml:42-88`) e contém `ArchflowTracer`, `OtlpTracerConfig`,
`ArchflowMetrics`, `PrometheusConfig`. `rg` por essas classes fora do próprio módulo e fora de testes
retorna **zero**. Nenhum span é aberto no motor, no executor de steps ou no laço de tool-calling.

**Métricas internas: existem e não chegam a lugar nenhum.** `MetricsCollector` é real
(`recordFlowStart`, `recordFlowCompletion`, `recordStepMetrics`, `recordFlowStatus`) e tem exporter
com backends Prometheus Pushgateway / InfluxDB / HTTP / log (`MetricsExporter.java:85-99`). Mas a
instância é criada **dentro** da factory, não injetada:

```java
// archflow-api/src/main/java/br/com/archflow/api/flow/FlowEngineFactory.java:57
MetricsCollector metrics = new MetricsCollector(AgentConfig.builder().build());
```

e o serviço de observabilidade da API recebe **`null`** no lugar dela:

```java
// archflow-api/src/main/java/br/com/archflow/api/config/ArchflowBeanConfiguration.java:184
return new ObservabilityService(null, traceStore, auditRepository.getIfAvailable(),
        eventStreamRegistry, runningFlowsRegistry);
```

**Trace store: nunca é escrito.** O motor aceita um `TraceRecorder`; existe um adapter pronto,
`TraceStoreRecorder` (`archflow-api/.../observability/impl/TraceStoreRecorder.java:27`), cujo javadoc
diz *"Wire this into the …"*. `rg -n 'TraceStoreRecorder' archflow-api/src/main` retorna **apenas o
próprio arquivo** — não é `@Bean`, não é passado à factory, que passa `null`
(`FlowEngineFactory.java:92`). Consequência: `InMemoryTraceStore` é criado
(`ArchflowBeanConfiguration.java:170`) e nunca recebe um trace.

> **Divergência com a documentação.** `CLAUDE.md` afirma que a "real observability today" é
> "API trace store + Actuator health". O trace store não tem escritor no código auditado.

**Cadeia de tool calls, latência, custo em token, taxa de falha por tool:** nada disso é observável.
O `McpAgentRunner` devolve `List<ToolCall>` ao chamador na memória do request
(`McpAgentRunner.java:53`), sem emissão. Há um `EventStreamRegistry`/SSE com `ToolEvent`
(`archflow-agent/.../streaming/domain/ToolEvent.java`), mas ele é alimentado pelo ciclo de vida do
**flow/step**, não pelo laço de tool-calling MCP.

### C12 — Multi-agente · `PARCIAL` · MÉDIO

**Delegação e sub-agentes: existem e estão ligados ao runtime.** `DynamicSupervisor` implementa
decompose → fan-out → verify → loop-until-dry sobre primitivas em `archflow-core/orchestration`
(`Orchestrator`, `Planner`, `Worker`, `Voter`, `BudgetLedger`), com convergência quando um round não
produz nada novo ou o orçamento acaba (`DynamicSupervisor.java:64-96`). É consumido de verdade:
`archflow-api/.../orchestration/DynamicWorkflowService.java` → `OrchestrateStep` (`StepType.ORCHESTRATE`).

Há também `AgentHandoffManager` (`archflow-agent/.../handoff/`) e uma fila de invocação
agente-para-agente com controle de recursão (`InvocationRequest.childInvocation`,
`recursionDepth`, `InvocationRequest.java:46-49`).

**O isolamento de C1 não vale entre eles.** O worker escolhe o sub-agente varrendo o catálogo global,
sem restrição herdada do supervisor:

```java
// archflow-agent/src/main/java/br/com/archflow/agent/orchestration/CatalogAgentWorker.java:42-47
Optional<ComponentQueryRouter.ScoredComponent> scored = router.route(subtask, ComponentType.AGENT);
if (scored.isEmpty()) { return Result.fail("no agent matched subtask: " + subtask); }
String componentId = scored.get().componentId();
AIComponent component = catalog.getComponent(componentId).orElse(null);
```

O `ExecutionContext` é repassado inteiro ao sub-agente (`CatalogAgentWorker.java:31,53`), sem
estreitamento de escopo. Um supervisor "somente leitura" pode, por roteamento semântico, delegar
para um agente de escrita.

**Contabilidade de custo existe** e é o único controle real de fan-out: `Usage` é lida dos metadados
do agente (`tokensUsed`/`costUsd`, `CatalogAgentWorker.java:63-66`) e alimenta o `BudgetLedger`, que
governa tanto o cap por item quanto a parada do loop.

### C13 — Testabilidade · `NATIVO` · —

Dá para testar um agente sem chamar LLM real, e o repositório faz isso de verdade.

**Costuras de injeção em todos os laços.** `McpAgentRunner` recebe um `LLMConfigResolver` que devolve
um `ChatModel` — interface do LangChain4j, trivialmente falsificável. `ConversationalAgent` define
sua própria abstração mínima e documenta a intenção: *"Determinístico e testável com uma função de
chat scriptada"*:

```java
// archflow-conversation/src/main/java/br/com/archflow/conversation/agent/ConversationalAgent.java:30-33
@FunctionalInterface
public interface ChatFunction { String reply(String prompt); }
```

`ConversationOrchestrator` isola o LLM num `LlmCaller`; `ReactAgentExecutor` recebe
`reasoningFunction` e `toolExecutor` como `Function`.

**Teste E2E real, não só unitário.** `QpAgentIntegrationTest` sobe um `HttpServer` da JDK como MCP
server stub e exercita `QpAgentService → McpAgentRunner (loop real) → HttpMcpClient (HTTP real)`,
com apenas o `ChatModel` encenado — inclusive verificando os headers `Authorization` e `X-TENANT-ID`
que chegam ao server (`QpAgentIntegrationTest.java:47-72`). Há também `MockChatModel` reutilizável
(`archflow-agent/src/test/java/br/com/archflow/agent/e2e/sac/MockChatModel.java`) e Testcontainers
no BOM para testes com Postgres real.

**Não existe:** framework de eval, gravação/replay de trace, harness de regressão de prompt. 286
arquivos de teste, sem gate de cobertura.

---

## Aderência ao caso de uso

### 1. O plano leitura/escrita cabe?

**Cabe, com uma fiação que não existe hoje — e a forma como cabe depende de qual dos dois caminhos
você adotar.**

**Caminho MCP (recomendado pela evidência).** Instanciar dois `McpAgentRunner` — nada impede, ele é
stateless e recebe o `McpClient` por parâmetro (`McpAgentRunner.java:70`). Cada um recebe um client
distinto:

- Agente de leitura → `McpClient` para os servers de telemetria (logs, métricas, traces).
- Agente de escrita → `McpClient` para os servers de remediação.

Se os conjuntos de tools estiverem em **servers MCP diferentes**, o isolamento é estrutural: o
agente de leitura não tem sequer o endpoint do server de escrita, e `listTools()` nunca lhe devolve
uma tool de escrita. Isso funciona **hoje, sem modificar o ArchFlow.**

Se os dois conjuntos convivem no **mesmo server** (o caso natural de um MCP server de Docker Swarm
que expõe `ps` e `service update`), aí não funciona: `McpToolSpecifications.from(client.listTools())`
traz tudo (`McpAgentRunner.java:81`), e não há filtro. É preciso (a) filtrar a lista antes do
`ChatRequest` e (b) revalidar antes do `callTool` — o segundo é obrigatório, porque um modelo pode
alucinar um nome de tool que não estava na lista enviada. Duas alterações de poucas linhas, no
mesmo arquivo. O `GovernanceProfile.isToolAllowed` já é a função de decisão pronta.

**Caminho de workflow (grafo).** Não cabe sem mudança estrutural. Dois `ComponentStep` no mesmo
processo compartilham o mesmo `ComponentCatalog` bean, e a resolução é `catalog.getComponent(id)`
com `id` vindo do JSON. Isolar exigiria introduzir escopo no catálogo ou instanciar catálogos
distintos por agente — o que quebra o bean único que todo o wiring assume
(`DefaultFlowStepFactory`, `CatalogAgentWorker`, `ComponentQueryRouter`, `CatalogController`).

**O gate de aprovação** entre propor e executar cabe no motor de grafo (`requestApproval`/
`submitApproval` são duráveis e sobrevivem a restart), desde que o gate seja uma **aresta do grafo**,
não uma pausa dentro de um raciocínio. Falta o produtor: hoje nenhum step nem endpoint chama
`requestApproval`, e a fila de aprovação exposta na REST é um `ConcurrentHashMap` sem produtor
(`ArchflowBeanConfiguration.java:285`).

### 2. Onde encaixa a fronteira Go?

**O ponto de acoplamento é a interface `McpClient`** — `archflow-langchain4j-mcp/.../McpClient.java`.
Servers MCP em Go (Docker Swarm, ArchGate, Traefik) entram como mais um endpoint HTTP ou mais um
subprocesso stdio. Não há nenhum ponto onde o ArchFlow exija Java do outro lado.

**Estabilidade do ponto:** boa, com duas ressalvas concretas.

- A interface é enxuta e defaults-driven (`listTools`, `callTool`, `listResources`, `readResource`,
  `subscribeToResource` — os não suportados devolvem `failedFuture(UnsupportedOperationException)`),
  o que a torna resistente a evolução. Mas **não há fronteira de API declarada** em lugar nenhum do
  projeto (sem `module-info`, sem `@PublicApi`), então "estável" aqui é convenção, não contrato.
- O `HttpMcpClient` implementa um **subset** do Streamable HTTP, sem SSE e sem session id
  (`HttpMcpClient.java:25`). Se os servers Go forem escritos contra a spec MCP completa e usarem
  notificações do servidor ou sessões, o cliente do ArchFlow não conversa — e o remendo é no
  ArchFlow, não no Go.
- Não há reconexão nem health check. Um server Go que reinicie deixa o `VendaxMcpClientProvider`
  (ou equivalente) com um client cacheado morto até um `configure()` explícito
  (`VendaxMcpClientProvider.java:46-52`).

Para stdio há um detalhe operacional: `McpCommandPolicy` tem allowlist de executáveis cujo default
não inclui binários arbitrários — um binário Go em `/usr/local/bin/opslenz-swarm-mcp` precisa ser
adicionado via `archflow.mcp.allowed-commands` (`McpCommandPolicy.java:38-42`).

### 3. O que quebra com 10 capability packs?

Na ordem em que satura:

**Primeiro: o contexto.** É o gargalo imediato e é o mais duro, porque não há nem medição nem
controle. `tools` é montado uma vez e enviado **inteiro a cada um dos 8 turnos**
(`McpAgentRunner.java:81,95`). Não há filtro, lazy loading, agrupamento nem limite configurável, e
nenhuma contabilidade de tokens do catálogo existe em lugar nenhum do código. Com 10 packs, o
catálogo cresce linearmente e você descobre o problema pelo custo da fatura ou por degradação de
qualidade, não por um número no dashboard — porque não há dashboard (C11).

**Segundo: o catálogo, por colisão de nomes.** Os dois registros são mapas planos sem namespace:
`DefaultComponentCatalog.components` é `componentId → AIComponent`
(`DefaultComponentCatalog.java:17`) e `DefaultToolRegistry` normaliza para minúsculas
(`DefaultToolRegistry.java:29`). `McpToolRegistry` **tem** prefixo por server
(`registry.callTool("server1:search", ...)`, `McpToolRegistry.java:33`) — mas o `McpAgentRunner`
não a usa, e diz por quê: *"Usa o McpClient diretamente (um server por execução…) — a McpToolRegistry
multiplexa vários servers, camada que não é necessária aqui"* (`McpAgentRunner.java:34-36`). Dez packs
com nomes genéricos (`search`, `list`, `status`) colidem silenciosamente, com last-write-wins.

**Terceiro: a memória.** Menos crítico do que parece, porque não há memória de longo prazo escrita
pelo runtime (C10). O que satura é o estado de trabalho: `FlowState.variables` vai inteiro para uma
coluna JSON a cada step concluído (`CheckpointingLifecycleListener.java:53-60` +
`JdbcStateRepository.java:73-83`), sem poda. Fluxos longos com outputs volumosos escrevem o mapa
completo repetidamente.

**Quarto: a concorrência.** É o menos preocupante. Virtual threads, semáforo global de 16 fluxos
concorrentes por default (`FlowEngineFactory.java:37`), backpressure com timeout de 30s para pegar
permit (`DefaultFlowEngine.java:267-270`), timeout de step de 10 min
(`DefaultFlowExecutor.java:38`). O desenho aguenta; o número é ajustável por parâmetro.

### 4. Qual a mudança mais invasiva?

**Introduzir "agente" como entidade de primeira classe do runtime — portadora de um escopo de tools,
uma política e uma identidade — e fazer *todos* os pontos de invocação de tool passarem por um único
ponto de decisão.**

Hoje há três invocações independentes, com três tipos de retorno diferentes e nenhum contrato comum:
`ComponentStep.java:56` (`Object`), `ConversationalAgent.java:122` (`String`),
`McpAgentRunner.java:110` (`McpModel.ToolResult` achatado em `String`). Unificá-las é a mudança que
destrava C1, C2 e C5 de uma vez — e é justamente o que o `ToolInterceptorChain` foi desenhado para
ser antes de virar código morto.

**Isso beneficia os outros consumidores.** O ArchFlow já *tem* as peças e já expõe as configurações
pela REST (`GovernanceProfileDto` com `enabledTools`/`disabledTools`,
`WorkflowConfigControllerImpl.java:168-169`) — configurações que o operador preenche na UI e que o
runtime ignora. Ligá-las converte uma promessa quebrada em feature real para qualquer tenant, não só
para o OpsLenz. O mesmo vale para `TraceStoreRecorder` (C11) e para `requestApproval` (C3): três
mecanismos completos esperando fiação.

O único ponto onde há tensão real é o **catálogo global**. Escopar `ComponentCatalog` por agente
quebra a suposição de bean único que atravessa `DefaultFlowStepFactory`, `CatalogAgentWorker`,
`DefaultComponentQueryRouter` e `CatalogController`. Consumidores que hoje contam com "um workflow
pode chamar qualquer componente" — que parece ser o modelo mental do designer visual — perderiam essa
liberdade. É a única mudança desta lista que retira capacidade de alguém.

---

## Riscos e incógnitas

**Onde tive baixa confiança:**

1. **Não executei o build nem a suíte de testes.** Toda a auditoria é leitura estática. Não posso
   afirmar que o projeto compila no estado atual, nem qual é a cobertura real. O `CLAUDE.md` afirma
   "minimum 80% required" mas não há goal `check` do JaCoCo no `pom.xml` — a afirmação não é
   verificável nem imposta.

2. **Rastreio de chamadores por `rg`, não por índice semântico.** Minhas afirmações de "código morto"
   (`isToolAllowed`, `ToolInterceptorChain`, `requestApproval`, `TraceStoreRecorder`, `EpisodicMemory.store`)
   se apoiam em busca textual sobre `--type java`. Invocação por reflexão ou por proxy Spring não
   apareceria. Mitiguei parcialmente: o repositório *usa* reflexão em pelo menos um ponto
   (`ArchflowBeanConfiguration.java:547-552`, `Class.forName` sobre nomes literais de plugin) e
   verifiquei que nenhuma dessas classes está nessas listas. Ainda assim, confiança ~90%, não 100%.

3. **Não avaliei o comportamento em execução do `LLMProviderHub` sob concorrência multi-tenant.**
   É um singleton estático com cache de modelos e overrides via `ThreadLocal`. Li a estrutura, não
   testei o comportamento. Se o OpsLenz for multi-tenant com chaves distintas, isso merece um teste
   dedicado antes de fechar o ADR de C7.

4. **Não inspecionei as migrations SQL.** Afirmo que `executionPaths` não é persistido com base no
   comentário do próprio código (`JdbcStateRepository.java:172-175`) e na ausência da coluna no
   `INSERT`/`SELECT`. Não abri os arquivos `V*.sql` para confirmar o schema completo, nem verifiquei
   se as migrations aplicam corretamente.

5. **Não avaliei o `archflow-ui`** além de contar linhas e confirmar a existência de
   `ApprovalDetailPage.tsx`/`approval-api.ts`. Se a UI depende de endpoints que servem dados vazios
   (a fila de aprovação, os traces), isso teria implicações operacionais que não medi.

6. **Não medi o comportamento do `HttpMcpClient` contra um server MCP real** que use a spec completa.
   Baseio a afirmação de "subset sem SSE" no javadoc e no código (`connect()` no-op, ausência de
   `Mcp-Session-Id`, ausência de handler de `text/event-stream`). Não testei interoperabilidade.

**O que seria preciso para determinar o que ficou aberto:**

- Rodar `mvn clean install` + `mvn jacoco:report` e ler o agregado — resolve (1).
- Um teste de integração que suba o contexto Spring com `archflow.persistence.jdbc.enabled=true`,
  execute um flow com aprovação e reinicie o processo — resolve (2) para o caminho crítico C3 e
  confirma empiricamente se o gate é alcançável de fora.
- Um MCP server de referência (o `mcp-inspector` oficial, ou um server Go real do OpsLenz) apontado
  para o `HttpMcpClient` — resolve (6) e é o teste mais barato de fazer antes de fechar o ADR da
  fronteira Go.

**Divergências doc↔código encontradas (reportadas conforme a regra 1):**

| Documento | Afirma | Código |
|---|---|---|
| `CHANGELOG.md:56-58` | Módulos `archflow-langchain4j-streaming`, `archflow-langchain4j-spring-ai`, `archflow-streaming`, `archflow-mcp` | Nenhum existe no reator |
| `CHANGELOG.md` ("Core Modules") | "Dynamic plugin loading with **Jeka**" | `ArchflowPluginManager` javadoc: *"nunca foi implementado"* |
| `CHANGELOG.md` (Fase 3) | "Observability — Prometheus metrics export and OpenTelemetry distributed tracing" | OTel: zero instrumentação; `MetricsCollector` chega como `null` no `ObservabilityService` |
| `CLAUDE.md` | "real observability today: API trace store + Actuator health" | `InMemoryTraceStore` não tem escritor: `TraceStoreRecorder` nunca é instanciado |
| `CLAUDE.md` | "JaCoCo para cobertura (mínimo 80% required)" | Sem goal `check`, sem regra de cobertura no `pom.xml` |
| `docs/PLANO_HOMOLOGACAO.md:59-64` | Item 1.5 "Human-in-the-loop real" marcado `[x]` concluído | Verdadeiro no motor; mas `requestApproval`/`submitApproval` não têm chamador REST nem `StepType`, e a fila de aprovação exposta na API é um registry em memória sem produtor |
| `docs/PLANO_HOMOLOGACAO.md` | 72 de 72 itens marcados `[x]` | Vários mecanismos "concluídos" existem sem fiação ao caminho de execução |

---

## Três respostas diretas

1. **O ArchFlow tem interrupt/resume durável que sobrevive a restart de processo?**
   **Parcial** — sim no nível do **step do grafo** (`AWAITING_APPROVAL` + `FlowState` em Postgres +
   resume incremental que pula steps concluídos, `DefaultFlowEngine.java:456-539`), mas **não** no
   nível do **turno do agente** (o histórico de mensagens do `McpAgentRunner` vive só em heap e o
   `MemoryRestorer` é passado como `null`), e o gate de aprovação durável **não tem endpoint REST nem
   step que o dispare**.

2. **O registro de tools é global ou por agente?**
   **Global** no caminho de workflow (um `ComponentCatalog` bean por processo, mapa plano
   `componentId → AIComponent`, `ArchflowBeanConfiguration.java:530`); **por instância — mas na
   granularidade de server inteiro, sem allowlist** no caminho MCP (`McpClient` por tenant,
   `listTools()` sem filtro). A allowlist por agente existe como código
   (`GovernanceProfile.isToolAllowed`) e **não tem nenhum chamador em produção**.

3. **A gravação em memória de longo prazo é controlável, ou é efeito colateral automático do laço
   do agente?**
   **Controlável** — na prática, porque não existe: `EpisodicMemory.store()` não tem nenhum chamador
   fora de testes, então nada é gravado automaticamente. O ponto de controle, se você quiser um,
   precisa ser criado; os ganchos existentes (`MemoryProvider`, `MemoryRestorer`) são de **leitura**
   e hoje estão desligados (`FlowEngineFactory.java:91`).
