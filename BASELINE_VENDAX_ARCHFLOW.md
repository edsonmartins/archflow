# Baseline — archflow — 2026-07-02

> Auditoria somente-leitura executada na branch `production-readiness` (HEAD `b11961c`, com mudanças locais não commitadas em ~27 arquivos de UI/API). Build, testes e subida local foram **executados de verdade**; toda afirmação cita evidência.

## 1. Identificação

- **Nome**: archflow — "Visual Java-Native Platform for AI Agent Workflows" (`readme.md:11`).
- **Propósito**: framework Java open-source (Apache 2.0) para construir, visualizar e orquestrar workflows de agentes de IA sobre LangChain4j, com designer visual drag-and-drop (React Flow) e backend Spring Boot. Serve como o "runtime substrate" de agentes do ecossistema IntegrAllTech (ADR-0001).
- **Stack e versões (verificadas no `pom.xml` raiz e `archflow-ui/package.json`)**: Java 25, Spring Boot **4.0.0**, LangChain4j **1.12.2**, Apache Camel 4.3.0 (dez/2023 — desatualizado), Maven multi-módulo (18 módulos + 16 submódulos langchain4j = 38 no reactor); frontend React 19.2, TypeScript 5.7, Vite 6.1, Mantine 9.3, `@xyflow/react` 12.10, `@ag-ui/client` 0.0.55 + CopilotKit 1.59.5. ~875 arquivos Java, 147 TS/TSX.
  - ⚠️ O `CLAUDE.md` do repo afirma "Java 17+, Spring Boot 3.3.0" — **desatualizado** vs. pom (Java 25 / Boot 4.0.0).
- **Último commit**: 2026-07-02 16:10 (`b11961c`) — **ativo hoje**. 136 commits nos últimos 3 meses. **1 contribuidor** (Edson Martins, 198 commits somando os dois aliases de `git shortlog`).
- **Governança**: 4 ADRs em `docs/adr/` + 6 design docs em `docs/design/` + `documentos/PLANO_PRODUCAO.md` (plano de prontidão com Fases 0–8 marcadas concluídas, corroborado pelo histórico de commits).

## 2. Saúde de build e testes

| O quê | Comando | Resultado real |
|---|---|---|
| Build backend completo (com testes) | `mvn clean install` | **BUILD SUCCESS**, 38 módulos, 1min27s (executado 2026-07-02 19:21) |
| Testes backend | (dentro do install; agregado dos surefire-reports de todos os módulos) | **1904 passando / 0 falhando / 0 erros / 9 pulados**. Maiores suítes: langchain4j 625, agent 236, api 203, model 183, conversation 155, security 132 |
| Build frontend | `cd archflow-ui && npm run build` | **OK** (`tsc -b && vite build`, 6907 módulos, 3.38s). Ressalva: a 1ª execução falhou com erros TS2304 em `NodePalette.tsx` por **cache incremental stale** do `tsc -b`; com `tsc -b --force` e na re-execução passou. Os símbolos vêm de `paletteSearch.ts`, arquivo novo ainda não commitado (WIP local) |
| Cobertura | JaCoCo configurado (0.8.13 no pom) | **NÃO VERIFICADO** número agregado — não rodei `jacoco:report` consolidado; CLAUDE.md exige 80% mínimo mas não confirmei o gate |

## 3. Capabilities e maturidade

Escala: 0 inexistente · 1 spec · 2 protótipo · 3 alfa · 4 beta/homologável · 5 produção.

| Capability | Maturidade (0-5) | Spec/Código | Evidência |
|---|---|---|---|
| Flow engine (execução assíncrona, steps, retry, paralelo) | **4** | código funcional + testes | `DefaultFlowEngine` (archflow-core) + `DefaultFlowExecutor` (archflow-agent); execução real testada ao vivo: `POST /api/workflows/wf-demo-001/execute` → 200 RUNNING → execução com 3 steps rastreados |
| Execução de workflow com nós de agente/LLM ("caminho clássico") | **2** | código parcial **quebrado** | Componentes built-in são heurísticos (regex, respostas enlatadas — `ConversationalAgent.defaultClassify()`), `AIComponent.initialize()` **nunca é chamado** → steps falham com `IllegalStateException`; palette da UI oferece `llm-chat`/`agent` sem componente correspondente no catálogo (`constants.ts:115-119`). Teste ao vivo: os 3 steps do workflow demo retornaram FAILED, e a API devolveu `error: null` (causa não exposta) |
| Orquestração dinâmica multi-agente (ADR-0002: plan/fanOut/verify/loopUntil, budget) | **3** | código funcional | `br.com.archflow.orchestration.DefaultOrchestrator` + `DynamicWorkflowService` → `LlmPlanner` chama `ChatModel.chat()` real; endpoint `POST /api/orchestration/run`; testes `DefaultOrchestratorTest`. Exige `ARCHFLOW_LLM_API_KEY` |
| AG-UI protocol + CopilotKit (ADR-0003) | **3** | código funcional | `AgUiController` (`POST /ag-ui/workflows/{id}` SSE), `AgUiAgentController` (chat streaming token-a-token com tool calls); frontend `agui-client.ts` + `CopilotAppOperator.tsx` com ~6 frontend tools. Limitação: chat single-turn sem tool-loop com streaming |
| Servidor MCP (workflows como tools) | **3** | código funcional | Testado ao vivo: `POST /mcp` respondeu `initialize` (protocolVersion 2025-06-18) e `tools/list` (tool `workflow_wf-demo-001`); `tools/call` roda pelo mesmo FlowEngine. Sem stream SSE (uma resposta por POST); sem auth quando `auth.enabled=false` (default) |
| Multi-LLM hub (16 providers; OpenRouter + fallback local/Ollama) | **3** | código funcional c/ lacuna | `LLMProviderHub` instancia modelos LangChain4j reais; adapter `OpenRouterChatAdapter` dedicado com fallback automático p/ `http://localhost:11434/v1` (Ollama); troca global via `archflow.llm.provider/base-url/api-key` (env). **Lacuna**: patch por-workflow/por-step (`FlowConfiguration.getLLMPatch()`) está modelado, persistido pela UI e testado, mas **não fiado no caminho de execução** — `forStep()` só aparece em teste |
| Designer visual (canvas, palette, YAML, i18n PT-BR/EN, a11y outline) | **4** | código funcional | Editor salva via `workflowYamlApi`, Run real via AG-UI; e2e Playwright (28 arquivos em `archflow-ui/e2e/`); build de produção OK. Ressalva: palette órfã (acima) |
| Painel admin (20+ páginas: tenants, global, billing, workspace, skills, MCP, triggers, observability) | **3** | código funcional | Todas wired a `/api/admin/*` reais — nenhuma página mock; porém seeds demo (`TenantControllerImpl.java:31-32`), usage com linha hardcoded (`GlobalConfigControllerImpl.java:104`) e stores in-memory |
| Auth JWT + RBAC + API keys | **3** | código funcional, desligado por default | `JwtService` (HS256), filtro `JwtAuthenticationFilter` com `archflow.security.auth.enabled:false` **default**; usuários apenas `InMemoryUserRepository` (sem tabela, sem registro); `ApiKeyAuthenticationFilter` existe mas **não está wired** no app |
| Multi-tenancy | **2** | código parcial | Column-based no engine (`flow_states` PK `(tenant_id, flow_id)`, `JdbcStateRepository` filtra por tenant); mas na API o header `X-Tenant-Id` é **aceito incondicionalmente** (`ImpersonationFilter`), tabela `flows` não tem tenant_id, e `InMemoryWorkflowRuntimeStore` ignora tenant |
| Persistência JDBC (flows, state, conversas, audit, chat memory) | **3** | código funcional, opt-in | `JdbcPersistenceConfiguration` (`archflow.persistence.jdbc.enabled=true`); migrations SQL existem mas **sem Flyway em nenhum pom/yml** (aplicação manual); `InMemoryWorkflowRuntimeStore` (workflows/execuções da API) **não tem alternativa JDBC**; Testcontainers PostgreSQL real em core/conversation |
| Aprovações HITL (suspend/resume) | **3** | código funcional | `/api/approvals` → `ApprovalQueueService` → `ApprovalRegistry`; armazenamento `ConcurrentHashMap` com expiração (volátil) |
| Observabilidade (Prometheus, OTLP, traces, audit) | **3** | código funcional | `ArchflowMetrics`, `OtlpTracerConfig`, `TraceStoreRecorder` grava trace por execução; mas só span raiz por flow (sem spans por step) e `InMemoryTraceStore` com cap |
| Conversação (tool-calling loop, guardrails PII/injection, governança, memória episódica) | **3** | código funcional (biblioteca) | `archflow-conversation` (57 arq, 26 de teste); `GovernanceResolver` etc. (ADR-0001 D3) |
| Eventos protobuf + agente standalone CLI | **3** | código funcional | `flow_events.proto`, `ProtobufEventPublisher` (retry/backoff), `POST /api/events/ingest`, `StandaloneRunner` roda YAML/JSON com `--events-url` |
| Cliente Brain Sentry | **2** | código parcial (ilha) | `archflow-brainsentry` completo e testado (29 testes), mas **nenhum módulo deployável depende dele**; controller da API só guarda config, nunca instancia `BrainSentryClient` |
| Integração Linktor | **3** | código funcional | `LinktorHttpClient` (Bearer/X-API-Key), escalation channel, flow publisher, nós de canvas `linktor-send`/`linktor-escalate`, e2e |
| Plugins dinâmicos | **2** | código parcial; **spec drift** | Loader = `URLClassLoader` simples por diretório (funciona, testado); **Jeka não existe no código** (zero ocorrências) apesar do CLAUDE.md afirmar; app Spring nem usa o loader (seed por reflexão hardcoded). Módulos `archflow-plugin-langchain` e `archflow-plugins-dist`: **mortos** (0 arquivos, fora do reactor) |
| Marketplace / Templates | **2-3** | código parcial | Registry, installer, assinatura RSA existem; 4 templates funcionais; sem loja real |
| Memória/vectorstores (Redis, JDBC, pgvector, Pinecone) | **3** | código funcional (biblioteca) | Adapters reais via SPI (pgvector o mais completo, 55 testes); porém **não exercitados pelo caminho de execução** atual (só listados no catálogo) |
| Workflow-as-tool, performance (caches/pools) | **2** | código parcial (ilhas) | Compilam e testam; zero consumidores externos aos próprios módulos |
| Realtime voz (WebSocket, OpenAI Realtime/Gemini Live) | **2-3** | código parcial | `/api/realtime/{tenantId}/{personaId}` + adapters; default `DevRealtimeAdapter` (mock) |

## 4. Superfícies expostas (o que dá para consumir hoje)

Testadas ao vivo com o app rodando localmente (jar `archflow-api-1.0.0.jar`, perfil dev, porta 16081):

- **REST funcional (verificado por chamada real)**:
  - `GET /api/workflows` → 200 (workflow demo semeado); CRUD completo + `POST /{id}/execute` (→ 200, execução real registrada) + `GET/PUT /{id}/yaml`.
  - `GET /api/executions`, `GET /{id}`, `POST /{id}/cancel|resume` → funcionais (com teste `SpringExecutionControllerTest`). Ressalva observada: execução FAILED retorna `error: null` — causa da falha não é exposta.
  - `/api/approvals` (+ `/archflow/approvals`), `/api/conversations`, `/api/catalog/*`, `/api/templates`, `/api/orchestration/run`, `/api/auth/*` (login/refresh/logout/me), `/api/apikeys`, `/api/admin/*` (tenants, global, workspace, skills, mcp, brainsentry, linktor, triggers, observability), `/archflow/assist/*` (nl-to-flow etc., protegido por header estático).
  - `/actuator/health` e `/api/health` retornaram **404** no perfil dev (listados como public paths no filtro, mas sem endpoint por trás) — não há health check consumível hoje.
- **MCP**: `POST /mcp` (JSON-RPC 2.0, subset Streamable HTTP sem SSE) — **verificado ao vivo**: `initialize` e `tools/list` respondem; 1 tool por workflow (`workflow_<id>`); `tools/call` executa pelo FlowEngine real.
- **AG-UI (SSE)**: `POST /ag-ui/workflows/{id}` (run com eventos RUN_*/STEP_*/STATE_SNAPSHOT) e `POST /ag-ui/agent` (chat streaming + TOOL_CALL_* para frontend tools do CopilotKit).
- **Eventos**: `POST /api/events/ingest` (protobuf) + `archflow-standalone` como emissor.
- **WebSocket realtime**: `/api/realtime/{tenantId}/{personaId}`.
- **Não existe**: broker de mensagens (zero Kafka/RabbitMQ), webhook genérico outbound (só o publisher Linktor), SDK Maven publicado (`archflow-standalone` é o "SDK" prático), `StreamController` é stub sem wiring.

## 5. Integrações consumidas

| Dependência | Onde | Configurável por tenant? |
|---|---|---|
| LLMs: OpenAI, Anthropic, OpenRouter (adapter dedicado c/ fallback local), Ollama, Gemini, Azure, DeepSeek etc. (16 no enum) | `archflow-langchain4j-provider-hub` + adapters SPI | **Global** via `ARCHFLOW_LLM_API_KEY`/`archflow.llm.*`; contrato per-tenant existe (`TenantKeyResolver`) mas o bean default é **NOOP**. Bedrock/Watsonx/Vertex declarados no enum porém sem dependência → `UnsupportedOperationException` |
| PostgreSQL + pgvector | datasource opcional da API; adapter pgvector | Global (env `SPRING_DATASOURCE_*`); datasource único, isolamento por coluna |
| Redis | chat-memory/vectorstore (Jedis) | Por instância de adapter (config map); não pub/sub |
| Pinecone | `vectorstore-pinecone` | Por adapter (`pinecone.apiKey` no config map) |
| **Linktor** (IntegrAllTech) | `LinktorHttpClient` na API — única API terceira realmente fiada em runtime | **Sim, per-tenant** (config em memória) + defaults `archflow.linktor.*` |
| **Brain Sentry** (IntegrAllTech) | módulo `archflow-brainsentry` | Config per-tenant existe, mas o cliente **não está integrado** ao app (ilha) |
| Prometheus/Grafana/Jaeger | archflow-observability + `docker-compose.monitoring.yml` | Global (env OTLP) |
| Mentors IPaaS / gestor-rq | — | **Zero código de integração** (gestor-rq só citado como prior art em ADRs) |

## 6. Multi-tenancy, segurança e dados

- **Tenancy**: column-based (`tenant_id`), primeira classe no engine (`flow_states`, `audit_logs`, scheduler por tenant, `TenantKeyResolver`), **frágil na borda**: `X-Tenant-Id` aceito do cliente sem validação quando auth desligada; tabela `flows` sem tenant_id; runtime store da API sem noção de tenant.
- **Auth**: JWT completo (access/refresh, blacklist, BCrypt, RBAC por aspecto) mas `archflow.security.auth.enabled` **default false**; sem Spring Security (filtros servlet puros); sem registro de usuários nem tabela — só `InMemoryUserRepository` (dev usa senha fixa `admin123`; fora de dev gera senha aleatória e **a loga em claro** — `ArchflowBeanConfiguration.java:~113`). `/mcp` desprotegido no modo default.
- **Segredos (paths, sem valores)**: default placeholder de JWT em `archflow-api/src/main/resources/application.yml:24`; senha dev hardcoded em `ArchflowBeanConfiguration.java`; credenciais dev no `docker-compose.yml:15,31`. Nenhuma chave real encontrada (varredura sk-/AKIA/ghp_/PEM limpa). Tipo `Secret` (char[], toString redigido, equals tempo-constante) + `ConfigSecrets.redactForLogging` implementados e testados.
- **Dados**: JDBC puro (sem JPA); migrations SQL estilo Flyway em 5 módulos (flow_states, flows, conversations, af_audit_log, chat_messages) mas **Flyway não está em nenhum pom/yml** → banco **não sobe do zero automaticamente** (aplicação manual documentada em `docs/development/production-persistence.md:34-36`). Testcontainers com PostgreSQL real existe, porém o teste do core cria tabelas com DDL inline (migrations não validadas ponta a ponta).
- **O que se perde no restart (config default)**: workflows/execuções do runtime da API (sem alternativa JDBC), definições de fluxo, estado do engine, usuários/sessões/API keys, aprovações, traces, filas de agente e triggers Quartz (RAMJobStore). `ProductionReadinessGuard` impede subir em prod com in-memory ativo — mas para UserRepository/ApiKeyRepository não há implementação durável no repo.

## 7. Dívidas, bloqueios e spec drift

- **Spec → código: surpreendentemente saudável.** Os 4 ADRs e 6 design docs estão **implementados com testes** (drift apenas de metadado: ADR-0001/0002/0003 ainda dizem "Proposto"). Zero TODO/FIXME reais em `src/main` (Java e TS).
- **Drift código → doc (o inverso do risco usual)**: `CLAUDE.md` com stack errada (Boot 3.3.0/Java 17 e "Jeka" — **Jeka não existe no código**); `docs-site` (Docusaurus) parado desde 2026-04-08 citando LangChain4j 1.10.0; `docs/roadmap.md` obsoleto ("Fase 1 em desenvolvimento").
- **Gap central UI↔runtime**: palette oferece nós `llm-chat`/`agent`/`assistant` sem componente executável; componentes built-in nunca são `initialize()`-ados → **workflow desenhado no editor com nó de IA falha em runtime** (verificado ao vivo, com `error: null` escondendo a causa).
- **Ilhas compiladas e não consumidas**: brainsentry, workflow-tool, performance, e os adapters de memória/vectorstore (prontos via SPI, fora do caminho de execução).
- **Mortos**: `archflow-plugin-langchain`, `archflow-plugins-dist` (0 arquivos, fora do reactor); lixo de bootstrap na raiz (`CodigosJava.txt` ×2 de fev/2025, `consolida_java.sh(.zip)`, `cria_modulos.sh`); `test-results/` não ignorado; `examples/` e `monitoring/` sem manutenção desde março.
- **Dependência desatualizada**: Apache Camel 4.3.0 (dez/2023, non-LTS). `@ag-ui/client` 0.0.55 (pré-1.0, API instável).

## 8. Prontidão para o VendaX

**O que o VendaX consegue usar HOJE:**
- O **runtime substrate como serviço em dev**: criar workflow via REST/YAML, executar (`POST /api/workflows/{id}/execute`), acompanhar execução com steps, aprovações HITL, e consumir tudo via **MCP** (`POST /mcp`) ou **AG-UI/SSE** (incluindo chat streaming com CopilotKit pronto no frontend).
- **Orquestração dinâmica multi-agente** (planner LLM + workers + budget) via `POST /api/orchestration/run` — o caminho LLM-real mais maduro.
- **Hub multi-LLM** com OpenRouter⇄local(Ollama) trocável por env — a exigência de "LLM local vs OpenRouter" está atendida no nível global.
- **Integração Linktor** já fiada (per-tenant) e painel admin completo para operar tenants/skills/MCP/observabilidade.

**O que precisa de trabalho antes de homologação (bloqueadores):**
1. **Nós de agente/LLM no workflow clássico** — hoje o desenho visual com IA não executa: criar componentes LLM reais no catálogo (`llm-chat` etc.), corrigir o ciclo `initialize()`, e fiar o `LLMConfigResolver` (patch flow/step/tenant) no `ComponentStep`. ~2-3 semanas-pessoa.
2. **Durabilidade**: runtime store JDBC (workflows/execuções), UserRepository/ApiKeyRepository persistentes, Flyway no classpath, triggers Quartz com JobStore JDBC. ~2 semanas-pessoa.
3. **Segurança de borda**: auth ligada por padrão em qualquer ambiente não-dev, proteger `/mcp`, derivar tenant só do JWT (matar o `X-Tenant-Id` cru), tenant_id na tabela `flows` e no runtime store, ativar `TenantKeyResolver` real (chave LLM por tenant). ~1.5-2 semanas-pessoa.
4. **Operabilidade**: endpoint de health real, expor causa de erro das execuções (`error: null` hoje), spans por step nos traces. ~0.5-1 semana-pessoa.

**Estimativa grosseira para "homologável" (maturidade 4): 6–8 semanas-pessoa**, assumindo 1 dev sênior que já conhece o código. A base é sólida (build verde, 1904 testes, specs implementadas); o esforço é de fiação e endurecimento, não de construção.

## 9. Resumo executivo em 5 linhas

1. Projeto **ativo e saudável**: build verde em 1min27s, 1904 testes passando, zero TODOs, 4 ADRs implementados com testes — mas é obra de **1 único contribuidor**.
2. O runtime **executa agentes LLM de ponta a ponta hoje** por 3 caminhos (orquestração dinâmica, chat AG-UI streaming, assist NL→flow); só LangChain4j — **LangGraph4j não é usado**.
3. **Gap central**: o workflow desenhado no editor visual com nós de IA **não chega a um LLM** (componentes heurísticos quebrados + palette órfã) — é a fiação mais urgente.
4. Troca de LLM **local⇄OpenRouter funciona globalmente** por env (adapter OpenRouter com fallback Ollama); por-workflow/tenant está modelada e testada mas não fiada; painel admin com 20+ telas reais.
5. Tudo é **volátil por default** (in-memory) e a auth vem **desligada**; com ~6-8 semanas-pessoa de fiação (LLM nos nós, persistência, segurança de borda) chega a homologável para o VendaX.

---

```yaml
baseline:
  projeto: "archflow"
  data: "2026-07-02"
  ultimo_commit: "2026-07-02"
  build_ok: true
  testes: "1904 passando / 0 falhando / 9 pulados"
  sobe_localmente: true   # jar direto (perfil dev, in-memory); docker-compose não testado (daemon parado)
  maturidade_geral: 3
  capabilities:
    - nome: "Flow engine (execução assíncrona REST + steps)"
      maturidade: 4
      estado: "funcional"
    - nome: "Workflow com nós de agente/LLM (caminho do editor visual)"
      maturidade: 2
      estado: "parcial"
    - nome: "Orquestração dinâmica multi-agente (planner LLM, budget)"
      maturidade: 3
      estado: "funcional"
    - nome: "AG-UI protocol + CopilotKit (chat streaming, frontend tools)"
      maturidade: 3
      estado: "funcional"
    - nome: "Servidor MCP (workflows como tools)"
      maturidade: 3
      estado: "funcional"
    - nome: "Hub multi-LLM (OpenRouter/Ollama/OpenAI/Anthropic, troca por env)"
      maturidade: 3
      estado: "funcional"
    - nome: "Designer visual (canvas, YAML, i18n, e2e)"
      maturidade: 4
      estado: "funcional"
    - nome: "Painel admin (tenants, observability, skills, MCP, triggers)"
      maturidade: 3
      estado: "funcional"
    - nome: "Auth JWT + RBAC"
      maturidade: 3
      estado: "parcial"
    - nome: "Multi-tenancy"
      maturidade: 2
      estado: "parcial"
    - nome: "Persistência JDBC (opt-in, sem Flyway)"
      maturidade: 3
      estado: "parcial"
    - nome: "Aprovações HITL (suspend/resume)"
      maturidade: 3
      estado: "funcional"
    - nome: "Observabilidade (Prometheus/OTLP/traces)"
      maturidade: 3
      estado: "funcional"
    - nome: "Integração Linktor (per-tenant)"
      maturidade: 3
      estado: "funcional"
    - nome: "Cliente Brain Sentry"
      maturidade: 2
      estado: "parcial"
    - nome: "Plugins dinâmicos (loader simples; Jeka inexistente)"
      maturidade: 2
      estado: "parcial"
    - nome: "Eventos protobuf + agente standalone CLI"
      maturidade: 3
      estado: "funcional"
    - nome: "Memória/vectorstores (pgvector, Redis, Pinecone, JDBC)"
      maturidade: 3
      estado: "parcial"
  superficies_consumiveis_hoje:
    - "REST /api/workflows (CRUD + /{id}/execute + YAML)"
    - "REST /api/executions (+cancel/resume)"
    - "REST /api/approvals (HITL)"
    - "REST /api/orchestration/run (orquestração dinâmica LLM)"
    - "REST /api/catalog, /api/templates, /api/conversations, /api/auth, /api/admin/*"
    - "MCP POST /mcp (tools workflow_<id>, JSON-RPC 2.0)"
    - "AG-UI SSE POST /ag-ui/workflows/{id} e POST /ag-ui/agent (chat streaming)"
    - "Ingest protobuf POST /api/events/ingest (+ standalone CLI emissor)"
    - "WebSocket /api/realtime/{tenantId}/{personaId} (voz)"
  bloqueadores_criticos:
    - "Nós de IA do editor visual não executam LLM (componentes heurísticos, initialize() nunca chamado, palette órfã llm-chat)"
    - "Estado volátil por default: workflows/execuções/usuários/API keys/aprovações perdidos no restart (runtime store sem alternativa JDBC)"
    - "Auth desligada por default; /mcp desprotegido; X-Tenant-Id aceito do cliente sem validação"
    - "Resolução LLM por-workflow/step/tenant modelada mas não fiada no caminho de execução"
    - "Sem Flyway: banco não sobe do zero automaticamente"
  esforco_para_homologavel: "6-8 semanas-pessoa"
  pronto_para_vendax: "parcial"
```
