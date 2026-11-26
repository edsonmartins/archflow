# Changelog

All notable changes to archflow will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2025-01-16

### Fase 1 - Foundation

- **LangChain4j 1.10.0 Integration** - Core adapter layer with SPI-based discovery for OpenAI, Anthropic, and 15+ LLM providers
- **Tool Interceptors** - Interceptor chain for tool calls with execution ID tracking (toolCallId system)
- **Streaming Protocol** - SSE/WebSocket streaming support for real-time agent responses
- **MCP (Model Context Protocol)** - Server and client implementation for standardized model communication

### Fase 2 - Visual

- **Web Component (`<archflow-designer>`)** - Framework-agnostic visual workflow builder, usable in React, Vue, Angular, or plain HTML
- **Node System** - Extensible node types for agents, tools, conditions, and data transformations
- **Canvas** - React Flow-based interactive canvas with drag-and-drop workflow editing
- **Execution Visualization** - Real-time execution state display within the visual designer

### Fase 3 - Enterprise

- **Auth & RBAC** - JWT authentication with role-based access control and audit logging
- **Observability** - Flow/step metrics with a pluggable exporter (Prometheus Pushgateway,
  InfluxDB, HTTP, log) and an in-process trace store behind the admin API.
  OpenTelemetry classes ship in `archflow-observability` but nothing instruments the runtime
  with them yet — no spans are emitted
- **Func-Agent** - Functional agent pattern for composable AI agent definitions
- **Multi-LLM Hub** - Unified interface to route between multiple LLM providers with fallback and load balancing

### Fase 4 - Ecosystem

- **Templates** - Pre-built workflow templates for common patterns (customer support, RAG, multi-agent)
- **Suspend/Resume** - Conversation persistence for multi-step workflows requiring human-in-the-loop
- **Marketplace** - Plugin and template marketplace infrastructure
- **Workflow-as-Tool** - Expose any workflow as a tool callable by other agents

### Core Modules

- `archflow-core` - Flow engine with workflow execution
- `archflow-model` - Domain models (Workflow, Flow, Node, Edge)
- `archflow-agent` - AI agent execution with tool support
- `archflow-plugin-api` - Plugin SPI for extensions
- `archflow-plugin-loader` - Fat-jar plugin loading with a child-first classloader (no runtime
  dependency resolution, no sandbox — only trusted jars)

### LangChain4j Modules

- `archflow-langchain4j-core` - Base adapter interfaces
- `archflow-langchain4j-openai` - OpenAI/GPT integration
- `archflow-langchain4j-anthropic` - Claude integration
- `archflow-langchain4j-openrouter` - OpenRouter integration
- `archflow-langchain4j-provider-hub` - Unified access to 15+ providers with runtime switching
- `archflow-langchain4j-mcp` - MCP client (HTTP subset + stdio) and server
- `archflow-langchain4j-memory-*` - Memory backends (Redis, JDBC)
- `archflow-langchain4j-vectorstore-*` - Vector stores (Pinecone, pgvector, Redis)

### Server & Infrastructure

- `archflow-api` - REST/WebSocket endpoints, SSE streaming, MCP client/server wiring
- `archflow-events-proto` - Event protocol shared between engine and UI
- `archflow-observability` - Audit trail (metrics/tracing classes exist but are not yet wired
  into the runtime — see below)
- `archflow-security` - JWT, RBAC and API keys
- `archflow-performance` - Caching library (not yet depended on by any other module)

### Performance Optimizations

- Caffeine-based caching with preset configurations
- Object pooling for expensive resources
- Connection pooling with health checking
- Parallel execution with virtual thread support (Java 21+)

### Documentation & Examples

- Docusaurus documentation site
- API reference (Core, Agent, LangChain4j, Streaming)
- Spring Boot, React, and Vue integration examples
- Customer support workflow demo

---

## [1.4.0] - 2026-09-24

> As versões 1.1.0 a 1.3.0 foram publicadas sem entrada aqui; esta seção cobre apenas o que mudou
> na superfície publicada no Maven Central (`archflow-model`, `archflow-dsl`, `archflow-sdk-java`)
> desde a 1.3.0.

### Added
- **`McpAgent`, na DSL** — a configuração do nó `mcp-agent` escrita por método em vez de por chave
  de texto: `systemPrompt`, `server`, `tools`/`noTools`, `maxIterations`, `outputFromTool`,
  `requireOutputFromTool`, `humanApproval`, `stopOnToolErrorCodes`, `model`, `maxTokens`,
  `reasoningEffort`/`reasoning`. `NodeSpec.with(...)` continua aceitando qualquer chave, que é o
  que mantém a DSL utilizável com um runtime mais novo que ela.
  - **`noTools()` existe porque o valor decide o comportamento, não a presença da chave:** `tools`
    ausente é "todas as ferramentas do servidor" e `tools: []` é "nenhuma" — o passo roda sem
    catálogo e sem abrir o servidor MCP. Quem digitava `.with("tools", List.of())` não via a
    diferença.
- **`WorkflowBuilder.step(String, McpAgent)`** — dispensa o `.spec()` no fim do nó.
- **`LLMConfigPatch.reasoning`** (`archflow-model`) — o objeto `reasoning` do provedor declarado no
  fluxo ou no passo, que atravessa até o corpo da requisição. É o único campo do patch que faz isso.
- **`LLMConfigPatch.cachePrompt`** (`archflow-model`) — marca o prefixo estável para cache onde o
  provedor exige a marca.

### Changed
- `docs/CATALOGO-NOS-FLUXO-LLM.yaml` descreve `tools` com os três casos (ausente, lista, lista
  vazia) e ganhou `encerrarEmCodigos` e a saída `encerradoPor` do nó `mcp-agent`.

### Notes
- O runtime (`archflow-api`) mudou bastante no período — `toolCalls` e encerramento no result do
  caminho de fluxo, reentrega do result, raciocínio mínimo no CS —, mas **não é publicado no
  Central**: quem consome os artefatos vê essas mudanças apenas pelo contrato que a DSL escreve.

## [1.5.0] - 2026-10-18

> A primeira versão publicada com o módulo `archflow-decision` e o nó `decision` no runtime
> (que entraram no ramo principal em 08/10, depois da `v1.4.0`). Pedido do VendaX de 14/10/2026
> (VEN-221). Como na 1.4.0, só `archflow-model`, `archflow-dsl` e `archflow-sdk-java` vão ao Central.

### Added
- **`Decision`, na DSL** — o nó `decision` escrito por método: `provider`, `model`, `keyRef`,
  `endpoint`, `choice`/`score`/`yesNo`, `state`, `thresholds`, `primary`, `gate`,
  `escalateOnError`/`failOnError`, `timeout`, `fallback`, `cascade`, `acceptAt`, `consensus`,
  `consensusEscalatesOnDisagree`, `providerOption(s)`. As chaves emitidas são as que o
  `DecisionComponent` lê — `DslDecisionConformanceTest` passa o documento pelo desserializador
  real e executa o nó. Sem pergunta, `spec()` recusa; a chave de API nunca entra no documento.
- **`Nodes.decision()`** — o nó cru, para quem prefere as chaves.
- **`WorkflowBuilder.route(from, to, branch)`** — aresta por **ramo** (`AUTO`/`REVIEW`/`ESCALATE`
  de um `decision`, `true`/`false` de um `condition`, um caso de um `switch`). Sai como `branch`
  na conexão; o motor monta a condição ao carregar. `WorkflowCodeGenerator` devolve `.route(...)`
  para uma conexão com `branch`.
- **`WorkflowBuilder.step(String, Decision)`** — dispensa o `.spec()`.

### Changed (runtime — não publicado no Central)
- **A saída de um nó `decision` que é o último passo vai ao Core** como envelope tipado
  (`decisao.<pergunta>.{valor, confianca, rota}`, `rota`, `confianca`, `principal`, `modelo`,
  `provedor`, `uso.{entrada, saida, custoUsd}`), em vez de "o fluxo não devolveu um JSON".
- **`answers.<id>.route` e `primary`** na saída do nó `decision`: a rota de cada pergunta sozinha,
  e qual pergunta virou `decision`.
- **`VendaxResult.uso.costReais`** (VEN-245): o mesmo custo de `costCents`, em reais e **sem
  arredondar**; ausente quando não há preço declarado, como `costCents` nulo. `costCents` segue
  arredondado para cima — é o que o teto lê.
- **`GET /actuator/info`** passa a dizer qual build está no ar: `build.version`/`build.time`
  (build-info do Maven) e `image.revision` (o commit da imagem, via `ARCHFLOW_BUILD_REVISION`,
  que `build-and-push.yml` passa ao Dockerfile). Pedido VEN-50.

## [Unreleased]

### Security — tenant da requisição (RCHFLW-13, RCHFLW-44, RCHFLW-49)
- **`X-Tenant-Id` não troca mais o tenant de quem tem JWT.** Com um access token válido, o header só
  pode repetir o tenant do token; um valor diferente recebe `403 TENANT_MISMATCH`. Um token **sem** a
  claim `tenantId` — todo token emitido pelo login hoje — é do tenant padrão `__default__`. A única
  forma de agir em outro tenant é a personificação verificada (`X-Impersonate-Tenant`, com
  `archflow.admin.impersonation.enabled` e papel admin/superadmin). Sem JWT (auth desligada), o
  header continua definindo o tenant.
  - **Quebra para agentes standalone:** quem chamava `POST /api/events/ingest` com JWT +
    `X-Tenant-Id: <tenant>` para publicar nesse tenant passa a receber 403. Ainda não há como
    emitir um token preso a um tenant; até lá, o caminho suportado é a personificação. Com JWT, os
    eventos são sempre fixados no tenant do token (`__default__` sem claim) e publicados em
    `__admin__:<esse tenant>` — antes, sem header, não eram fixados e cada evento ficava com o
    tenant do próprio payload.
  - O workspace (`WorkspaceControllerImpl`) não lê mais os headers crus como fallback.
- **Tenant padrão único: `__default__`.** O motor gravava `SYSTEM` para execução sem tenant e a UI
  inventava `default`; agora todos usam `__default__` (`DefaultTenant.ID`). As migrações
  `V1_3_1` (`flow_states`, `audit_logs`) e `V6_12_1` (`mcp_agent_states`, coluna e JSON) trazem o
  dado antigo `SYSTEM` para o novo id. `V1_3_1` chega **fora de ordem** num banco já em 6.12 e
  depende de `spring.flyway.out-of-order=true` (padrão do perfil prod, como a `V1_3`): com
  `SPRING_FLYWAY_OUT_OF_ORDER=false` o boot para em "Detected resolved migration not applied".
- **Execuções por REST (`POST /api/workflows/{id}/execute`), editor (AG-UI) e MCP (`tools/call`)
  rodam com o tenant da requisição**, não mais com `SYSTEM`: estado, aprovações, traces, cache de
  adaptador e `TenantKeyResolver` recebem o tenant real. Um `TenantKeyResolver` próprio que tinha
  chaves sob `SYSTEM` passa a ser consultado com o tenant real (ou `__default__`). O Run do editor
  passa a enviar a personificação ativa (`X-Impersonate-Tenant`), como a lista de fluxos.
- **`GET /api/approvals/pending` e `/pending/count` sem `tenantId`** devolvem a fila do tenant da
  requisição; antes a ausência significava "todos os tenants". `tenantId=all` e valores explícitos
  seguem como estavam (a checagem de quem pode pedir o quê é o RCHFLW-39).
- **Stream SSE só do próprio tenant (RCHFLW-49).** `GET /api/stream/{tenantId}/{sessionId}` responde
  `403` (sem corpo) quando `{tenantId}` não é o tenant efetivo da requisição — o do token, ou o
  personificado por um superadmin com papel conferido no servidor — e não registra o emitter. O canal
  administrativo só abre para o próprio tenant: `/api/stream/__admin__/<meu tenant>`; personificar o
  id `__admin__` não alcança o de outro. Antes qualquer usuário autenticado lia em tempo real o chat e
  os eventos de execução de outro tenant conhecendo os ids.
  - **Nova rota `GET /api/stream/{sessionId}`**: o servidor usa o tenant da requisição. É a que o chat
    usa; a UI parou de mandar o tenant da conversa ou `default`. Passa pela mesma conferência: com o
    tenant efetivo igual ao id reservado `__admin__`, `/api/stream/<B>` responde `403` em vez de abrir
    `__admin__:<B>`.
  - **Ingest também não escreve no canal de outro tenant.** `POST /api/events/ingest` usava o
    `executionId` do corpo como chave do canal, e um agente do tenant A entregava eventos em
    `<B>:<conversa>` ou `__admin__:<B>`. Agora um id com `:` só passa quando começa pelo tenant da
    requisição; `__admin__:...` nunca (o servidor já publica nele). O evento recusado conta em
    `rejected`. Ids de execução comuns (sem `:`) não mudam.
  - **Quebra:** `/api/stream/default/...` deixa de funcionar para o tenant padrão — o id é
    `__default__` (ou use a rota sem tenant). A resposta do chat não é mais publicada também em
    `default:<conversa>`; mensagem sem tenant (auth desligada) publica em `__default__:<conversa>`.
  - O cliente SSE da UI manda a personificação ativa (`X-Impersonate-Tenant`) e não reconecta após
    401/403. Live Events aceita o tenant em branco (= o da sessão).

### Security — fluxos, execuções e conversas por tenant (RCHFLW-73, RCHFLW-71)
- **Workflows e execuções são do tenant da requisição.** `GET/PUT/DELETE /api/workflows/{id}`,
  `/status`, `/publish`, `/versions`, `/deployments`, `/execute`, `GET/PUT /api/workflows/{id}/yaml`,
  `GET /api/executions`, `/{id}`, `/{id}/cancel`, `/{id}/resume`, o Run do editor (AG-UI) e o MCP
  (`tools/list`, `tools/call`) só alcançam os do tenant da requisição. O de outro tenant responde
  **404** (MCP: some do `tools/list` e o `tools/call` responde "Unknown workflow"), sem revelar que o
  id existe. Instalar um template cria o rascunho no tenant de quem instalou. Não há leitura "todos
  os tenants": o superadmin age sobre outro tenant só personificando.
  - O registro de execução ganhou o campo **`tenantId`** (resposta de `GET /api/executions`).
  - `WorkflowRuntimeStore` mudou de contrato: todo método por workflow, a listagem e a leitura de
    execução recebem o tenant; `putExecution` e o `executions()` sem argumentos saíram. Um store
    próprio precisa ser adaptado.
  - Dado anterior: a migração **`V6_12_2`** acrescenta `tenant_id` a `workflow_documents` e
    `workflow_executions` e põe toda linha existente no tenant padrão `__default__` — onde ela sempre
    apareceu para quem entra sem personificar. O JSON antigo das execuções não é reescrito; o tenant
    vem da coluna. Depois do preenchimento a coluna fica sem DEFAULT: um INSERT sem tenant falha. Num
    banco que já tenha aplicado uma migração acima de 6.12.2, ela chega fora de ordem e depende de
    `spring.flyway.out-of-order=true` (padrão do perfil prod). O fluxo de demonstração do dev é do
    tenant padrão.
- **Conversas são do tenant que as criou (RCHFLW-71).** `POST /api/conversations` grava o tenant da
  requisição na mensagem de sistema que abre a conversa (`metadata.tenantId`); a primeira mensagem
  enviada para um id ainda sem conversa faz o mesmo, e `POST /suspend` grava o tenant no contexto da
  conversa suspensa. Lista, detalhe, mensagens, envio, `resume` (pelo token), `suspend` de uma
  conversa existente e cancelamento de uma conversa de outro tenant respondem **404**. O campo
  `tenantId` da conversa passa a ser o dono real (antes era sempre `"default"`); conversa sem tenant
  gravado é do tenant padrão `__default__`.
- UI: ao entrar num tenant ou sair da personificação, a lista de fluxos, o fluxo aberto e as execuções
  em memória são descartados e a lista é recarregada — uma resposta atrasada do tenant anterior é
  ignorada.

### Changed — nós de controle falham fechado (RCHFLW-123, RCHFLW-135)
- **Quebra: `condition`/`switch` com referência sem valor falham o passo.** Um `${…}` que não chega a chave
  nenhuma na execução fazia a condição dar `false` e o switch ir ao `default`; agora o passo falha nomeando o nó,
  o campo e a referência. O portão `${entrada.valor} > 500` com entrada em texto livre confirmava um reembolso
  de R$ 900 sem aprovação. A condição usa lógica de três valores: só falha quando o resultado depende da
  referência (`${D.answers.x.result} == true || ${D.route} == 'ESCALATE'` continua mandando a decisão que falhou
  ao humano). Valor gravado como `null` é valor; `${input}` sozinho sem dado vale `""` (RCHFLW-101). Uma
  comparação de ordem (`>`, `>=`, `<`, `<=`) entre número e não número (`"R$ 900"`, `"1.200,00"`) também falha.
  Quem sente: a condição sobre uma chave do JSON de entrada que nem toda execução traz; o switch sobre
  `${D.answers.<id>.choice}` quando a decisão falha (`answers` vem vazio — use `${D.decision}`); no VendaX, a
  condição sobre `texto`, `payload.*` ou uma chave `vendax.*` (task, identidade, correlação) que o invoke não traz
  ou traz em branco (o Core recebe `status: ERROR`). Arestas com condição à mão não mudam (seguem
  `archflow.flow.strict-conditions`).
- **Quebra: switch sem caso nem saída falha.** O valor que não casa com nenhum caso, num switch sem aresta
  `default`, terminava `COMPLETED` sem chegar a uma saída; agora falha dizendo o valor e os casos. Ligue uma
  aresta `default`. O caso casado sem aresta continua terminando ali.
- **Quebra: nó solto é recusado antes do primeiro passo** (`NODE_DISCONNECTED`). Num fluxo com arestas, um nó
  sem nenhuma aresta rodava à parte, antes de qualquer portão (no QA, uma Saída antiga rodou antes da
  aprovação). Fluxos sem arestas (lineares, de um nó) não mudam. **Sem chave de compatibilidade**: ligue ou apague
  o nó. O `WorkflowBuilder.build()` da DSL ainda não confere isso — o fluxo é recusado só ao executar.
- **VendaX: o `error` do `VendaxResult` traz o motivo, não o invólucro do motor.** Um fluxo recusado na validação
  (o nó solto acima, um nó sem execução) chegava ao Core como `Falha ao executar o fluxo: Error executing flow:
  <uuid>`; agora vem `Falha ao executar o fluxo: Flow validation failed: 1 errors found: … nó(s) sem nenhuma
  aresta: sobra …` — o texto que a tela de execuções já mostrava. Vale a mensagem mais funda da cadeia que tem
  texto (raiz sem mensagem não apaga a frase de cima). Prefixo e `status: ERROR` não mudam; quem casava o texto
  do invólucro precisa revisar.
- **`archflow.flow.lenient-conditions=true`** devolve o comportamento antigo das duas primeiras (ramo
  `false`/`default` com `warning`, switch sem saída conclui); padrão `false`, para migração. Não cobre o nó solto.
  Standalone e `EmbeddedWorkflowRunner`: `ARCHFLOW_FLOW_LENIENT_CONDITIONS=true` no ambiente do processo. No
  servidor, a mesma variável vale (o Spring a liga à propriedade), ou `-Darchflow.flow.lenient-conditions=true`.
  - **Docker com lista `environment:` explícita** (como o `docker-compose.prod.yml`): uma variável só no `.env`
    **não chega** ao container. Acrescente à lista do serviço `app` a linha
    `- ARCHFLOW_FLOW_LENIENT_CONDITIONS=${ARCHFLOW_FLOW_LENIENT_CONDITIONS:-false}`, ou passe o `-D` pelo
    `JAVA_OPTS`, que o `ENTRYPOINT` da imagem expande (`java $JAVA_OPTS -jar app.jar …`). Definir `JAVA_OPTS`
    substitui o padrão da imagem (`-Xmx512m -Xms256m`): repita a memória
    (`JAVA_OPTS=-Xmx512m -Xms256m -Darchflow.flow.lenient-conditions=true`). Se o compose do deploy só repassa
    uma variável para o `JAVA_OPTS`, o `-D` vai nela.
  - Detalhes e exemplos: `docs/decision-component.md`, seção "Falhar fechado".

### Added
- **`APPROVAL` step type** — the producer the human-in-the-loop gate was missing. A flow node of
  this type suspends the run in `AWAITING_APPROVAL` with a proposal, durably, and continues only
  after a decision. Downstream steps branch on `${__archflow.approvalDecision}` and read the
  human-edited payload from `approvalPayload`.
- **`ToolAccessPolicy`** — per-agent tool allowlist enforced inside the MCP tool-calling loop,
  both when building the catalogue sent to the model and again before each `callTool`.
  `McpAgentRunner` now requires one, so "no restriction" has to be written
  (`ToolAccessPolicy.allowAll()`) instead of resulting from an omission.
- **`StateManager.findByStatus`** (and `StateRepository.getStatesByStatus`) — lets the API build
  queues from durable flow state instead of an in-memory index.

### Fixed
- **Human-in-the-loop approvals were unreachable.** `requestApproval`/`submitApproval` were
  implemented and durable but had no caller: no step type invoked them and no endpoint reached
  them. Meanwhile `/api/approvals/*` served an in-memory `ApprovalRegistry` whose `register()`
  had no producer, so the queue was permanently empty and would not have survived a restart.
  The queue is now derived from durable flow state and decisions go to the engine.
- **The approval proposal was discarded.** `requestApproval(flowId, stepId, proposal)` never
  stored `proposal`, leaving the reviewer with nothing to review. It is now persisted with the
  request, alongside the step id and the request timestamp.
- **A malformed tool call invoked the tool anyway.** When the model emitted arguments that were
  not valid JSON, `McpAgentRunner` logged a warning and called the tool with an empty map —
  executing a different action from the one requested. The tool is no longer invoked; the error
  goes back to the model, which can retry.
- **The trace store had no writer.** `TraceStoreRecorder` was never registered as a bean and the
  engine received `traceRecorder = null`, so the admin observability screens could only ever be
  empty. It is now wired, and flows that fail produce a trace too (previously only the success
  path notified the recorder).
- **Flow metrics never reached the API.** The engine created a private `MetricsCollector` while
  `ObservabilityService` received `null`. Both sides now share one collector.

### Planned
- Extension marketplace launch
- Additional workflow templates
- Enhanced debugging and visual execution tracing
- Additional LLM provider adapters
