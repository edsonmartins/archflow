# Plano de Prontidão para Produção — ArchFlow

> Origem: auditoria completa de 11/06/2026 (backend, serviços, plugins/LangChain4j, frontend, docs).
> Objetivo: eliminar TODOS os gaps levantados e fechar com re-auditoria. Estimativa total: ~8–9 semanas (1 dev) ou ~5–6 semanas com paralelização (backend + frontend em paralelo).

## Status (atualizado 11/06/2026)

- ✅ **Fase 0** — baseline verde; JAR standalone medido: 19 MB
- ✅ **Fase 1** — RSA real no marketplace; credenciais default eliminadas; allow-list MCP; SecureRandom/CORS/resume tokens
- ✅ **Fase 2** — DI no ArchFlowAgent; JdbcFlowRepository/JdbcConversationRepository/JdbcPromptRegistry + migrations; ProductionReadinessGuard; cache Redis JSON + chaves tenant-aware
- ✅ **Fase 3** — FlowPluginManager reescrito sobre o plugin-loader (API pública de carga criada); falhas de plugin explícitas
- ✅ **Fase 4** — circuit breaker BrainSentry; retry no ProtobufEventPublisher; blacklist JWT no logout; fail-fast na restauração de memória; varredura de exceções engolidas (auditoria, WorkflowTool, skills, agente conversacional)
- ✅ **Fase 5** — refresh automático de token + validação de expiração; motion no canvas; display font (Bricolage Grotesque); EmptyState; limpeza index.css; **strict mode ligado com o app INTEIRO no type-check** (tsconfig excluía components/pages/stores/services — 2 bugs reais de produção encontrados: TenantInfo passado como objeto à API na impersonação, prop `in` do Collapse ignorado no Mantine 9); split do PropertyPanel (1640→1018 linhas, AgentFields + 4 field components extraídos); lazy-load de 17 telas secundárias; badge de catálogo offline. Restante (outline a11y do canvas, Playwright admin) movido para a Fase 7
- ✅ **Fase 6** — readme corrigido (Spring Boot, 19 MB; CoT-SC verificado como IMPLEMENTADO — ChainOfThoughtStrategy); docs/architecture/internal-modules.md cobre os 7 módulos órfãos; ADR-0004 registra a decisão sobre ExecutionContext; índice de fontes de verdade em docs/readme.md
- ✅ **Fase 7** — Testcontainers com PostgreSQL 16 real (caminho de escrita do JdbcStateRepository — ON CONFLICT/::json — agora coberto; migration de conversação validada; sobrevivência a restart testada). Constatado que CI já tinha gates (JaCoCo 60/80, lint, unit, Playwright com 26 specs incluindo admin — o achado da auditoria estava desatualizado). Nice-to-have remanescente: ampliar cobertura unitária dos adapters vectorstore/memory
- 🔶 **Fase 8** — re-auditoria em andamento

Nota da execução: o JdbcStateRepository existente usa SQL específico de PostgreSQL (ON CONFLICT/::json) não exercitável no H2 — cobertura de escrita ficará nos Testcontainers da Fase 7. O wrapper Secret para API keys dos adapters foi adiado para a Fase 7 (custo/benefício baixo: nenhum site de log imprime configs hoje).

## Decisões assumidas (ajustar se discordar)

| # | Decisão | Recomendação assumida |
|---|---------|----------------------|
| D-A | CoT-SC (prometido no readme, não implementado) | **Remover do readme agora** (Fase 6) e implementar depois como feature — prontidão de produção ≠ feature nova |
| D-B | Marketplace (órfão + segurança fake) | **Corrigir a segurança** (Fase 1) e manter atrás de feature-flag desabilitada por padrão até ter consumidor real |
| D-C | Backend de persistência canônico | **PostgreSQL** (já está no docker-compose; pgvector já existe) com Redis para cache/memória de sessão |

---

## Fase 0 — Preparação (1 dia)

- [ ] Branch `production-readiness` a partir da main; build `mvn clean install` verde como baseline.
- [ ] Registrar versão do JAR standalone atual (`ls -lh archflow-standalone/target/*.jar`) para corrigir claim do readme (Fase 6).
- [ ] Ativar JaCoCo report como baseline de cobertura.

## Fase 1 — Segurança crítica (≈1 semana) 🔴

**1.1 Assinatura RSA real no Marketplace**
- `ExtensionSignatureValidator.java:70-78` — implementar verificação real com `java.security.Signature` (SHA256withRSA) contra `trustedKeys`; remover o `log.debug` que retorna sucesso incondicional.
- `ExtensionInstaller.java:186-195` — rejeitar assinaturas que só "parecem" válidas (prefixo `SHA256:`/`RSA:`); exigir verificação criptográfica; remover possibilidade de `verifySignatures=false` fora de profile dev/test.
- `ExtensionInstaller.java:248` — substituir `"1.0.0"` hardcoded por versão lida do `pom.properties`/manifest do JAR.
- `PermissionValidator.java:107-110` — wildcard matching estrito (segmentado por `:`; `network:*` não pode casar com escopo de outra categoria).
- Adicionar audit log de install/uninstall de extensões.
- **Aceite:** teste que tenta instalar extensão com assinatura forjada e FALHA; teste de versão incompatível rejeitada.

**1.2 Credenciais padrão**
- `archflow-ui/src/pages/LoginPage.tsx:101` — remover `admin/admin123` da UI (exibir só se `import.meta.env.DEV`).
- `ArchflowBeanConfiguration.java:77` + `InMemoryUserRepository.java:42` — seed do admin via env (`ARCHFLOW_ADMIN_PASSWORD`); sem env definida em profile não-dev: gerar senha aleatória e logar UMA vez no boot, ou falhar o startup.
- **Aceite:** build de produção não contém a string `admin123`.

**1.3 MCP — execução de subprocessos**
- `StdioClientTransport.java:64-78` — whitelist de executáveis permitidos (configurável via properties), validar path absoluto/sem symlink, sanitizar `extraEnvironment` (bloquear LD_PRELOAD, PATH override etc.).
- **Aceite:** teste que tenta spawnar binário fora da whitelist e é rejeitado.

**1.4 Higiene criptográfica**
- `PasswordService.java:114-129` — `Math.random()` → `SecureRandom`.
- `CorsConfiguration.java:54` — ambiente desconhecido deve cair em **production** (mais restritivo), não development.
- `SuspendedConversation` — resume tokens com `SecureRandom` + rate limiting de tentativas de resume.

## Fase 2 — Persistência de produção (≈1,5 semana) 🔴

**2.1 Injeção de repositórios no ArchFlowAgent**
- `ArchFlowAgent.java:58-59` — novo construtor `ArchFlowAgent(AgentConfig, StateRepository, FlowRepository)`; construtor atual delega com defaults in-memory (compatibilidade); `archflow-standalone` continua in-memory (correto para one-shot).

**2.2 Implementações JDBC (PostgreSQL) onde só existe in-memory**
- `JdbcFlowRepository` e `JdbcStateRepository` (archflow-core/api) com schema Flyway/Liquibase.
- `JdbcConversationRepository` (+ TTL/cleanup) para `InMemoryConversationRepository`.
- `JdbcPromptRegistry` para versionamento de prompts.
- Memória episódica: persistir em pgvector (módulo já existe) em vez de keyword-match in-memory.
- Quartz: `JDBCJobStore` como default em profile prod.
- Auditoria: `JdbcAuditRepository` como default em prod (já existe; revisar parametrização de SQL contra injection).

**2.3 Guard de produção contra beans in-memory**
- `ArchflowBeanConfiguration` — `@PostConstruct` que inventaria beans efetivos; se profile ≠ dev/test e houver `InMemory*` (FlowRepository, StateManager, TraceStore, AgentInvocationQueue, ApiKeyRepository, UserRepository, AuditRepository, RAMJobStore), **falhar o startup** com mensagem clara (override por flag `archflow.allow-in-memory=true`).
- **Aceite:** subir com profile prod sem Postgres configurado → startup falha com mensagem acionável; teste de integração com Testcontainers provando persistência sobrevive a restart.

**2.4 Cache Redis seguro**
- `RedisCacheManager.java:265-287` — serialização Java → JSON (Jackson) com allowlist de tipos.
- `CachingApiKeyService` — incluir `tenantId` na chave de cache.
- **Aceite:** teste multi-tenant provando isolamento de chaves.

## Fase 3 — Sistema de plugins (≈1 semana) 🔴

- `FlowPluginManager.java:46-125` — **reescrever** o carregamento usando o `ArchflowPluginManager`/`ArchflowPluginClassLoader` do `archflow-plugin-loader` (que está pronto e testado), em vez de ressuscitar o código comentado baseado em download Jeka. Se download remoto de plugins não for requisito v1: carregar apenas de diretório local (`config.pluginsPath()`) e documentar.
- Falha ao carregar plugin de um step → **erro explícito** no fluxo, nunca no-op silencioso.
- `ArchflowPluginManager.java:58` — não passar `null` em `onLoad()`; criar `ExecutionContext` mínimo.
- Plugin discovery silencioso (`ArchflowBeanConfiguration.java:442-444, 483-484`) — coletar falhas e expor em endpoint de health/actuator + log WARN.
- (Opcional, recomendado) Verificação de assinatura de JAR de plugin reutilizando a infra da Fase 1.1.
- `archflow-plugin-langchain` (pom vazio) — remover do reactor ou documentar propósito.
- **Aceite:** E2E carregando um plugin real de diretório; teste de plugin ausente gerando erro claro.

## Fase 4 — Resiliência e observabilidade (≈1,5 semana) 🟠

- **BrainSentry** (`BrainSentryClient.java:72, 115-116, 140-146`): circuit breaker (Resilience4j), distinguir "sem resultados" de "erro de API" no tipo de retorno, métricas de falha; manter fallback gracioso mas **visível** (WARN + métrica).
- **Eventos Protobuf** (`ProtobufEventPublisher.java:104-109, 178-183`): retry com backoff exponencial, métrica/contador exposto de eventos descartados, gzip no payload, versão de schema no envelope.
- **JWT blacklist** (`AuthService.java:225-235`): implementar com Redis (TTL = expiração do token); logout passa a invalidar de fato.
- **Memória do agente** (`DefaultFlowEngine.java:200-202`): falha na restauração → configurável `fail-fast` (default em prod) vs `continue-with-warning` (dev).
- **Auditoria in-memory** (`InMemoryAuditRepository.java:57-61`): WARN + métrica quando evictar eventos.
- **Varredura de exceções engolidas**: revisar os ~10 pontos mapeados (subscribers de conversação `ConversationManager.java:327`, tool errors `ConversationalAgent.java:120`, cache get/put `RedisCacheManager.java:128`, summarizer) — regra: logar com contexto + métrica; engolir só quando explicitamente não-crítico e comentado.
- **WorkflowTool** (`WorkflowTool.java:150-161`): sem executor configurado → lançar `IllegalStateException` no build, não retornar placeholder.
- **Skills** (`FileSystemSkillLoader`): validação de path traversal.
- **API keys**: wrapper `Secret` (char[] + redação em toString/logs) nos configs dos adapters.
- **Aceite:** testes de caos básicos — BrainSentry fora do ar não derruba fluxo e fica visível em métrica; logout invalida token (request seguinte com 401).

## Fase 5 — Frontend (≈1,5 semana, paralelizável com Fases 2–4) 🟠

**5.1 Auth**
- Refresh automático de token: interceptor no `api.ts` que chama `authApi.refresh()` antes da expiração (decodificar `exp` do JWT) com fila de requests durante o refresh.
- `ProtectedRoute` — validar expiração do token, não só presença.

**5.2 Qualidade de código**
- `tsconfig.app.json` — `strict: true` gradual: começar por `stores/`, `services/`, auth; eliminar os 14 `any` (substituir por `unknown` + type guards).
- `PropertyPanel.tsx` (~1.700 linhas) — dividir em painéis por categoria (AgentPanel, ToolPanel, VectorPanel, ControlPanel, McpPanel…) + hooks compartilhados.
- Remover boilerplate Vite do `index.css` (`#646cff` etc.).
- Lazy-load de templates/marketplace/playgrounds.

**5.3 Design (lente frontend-design)**
- **Motion no canvas**: entrada de nós com stagger, indicação de fluxo de dados nas edges durante execução (dash animado direcional já existe — evoluir), transição visual de estado do nó (idle→running→completed/failed) com pulso na borda, micro-interações no NodePalette (hover/drag). CSS-first; Motion (framer) apenas se necessário.
- **Identidade tipográfica**: adotar display font característica para headings (manter DM Sans no corpo, DM Mono no técnico) — proposta a validar com 2–3 opções.
- **Acento de marca**: refinar paleta além do azul-SaaS genérico `#2563EB` — um acento próprio para momentos-chave (CTA, execução ativa), mantendo os tokens semânticos.
- **`EmptyState` padrão**: componente único (ilustração leve + título + ação) substituindo os estados vazios ad-hoc.
- **A11y**: outline textual do workflow (lista de steps navegável por teclado) como visão alternativa ao canvas; aria-labels nos nós.

**5.4 Mocks/fallbacks**
- Manter fallbacks offline-first, mas marcar na UI quando dados exibidos são fallback (badge "offline/demo") para nunca confundir com dados reais.

**Aceite:** lint+build verdes com strict ligado nos módulos migrados; sessão não expira durante uso ativo; canvas com motion aprovado visualmente.

## Fase 6 — Documentação (≈3 dias) 🟡

- readme.md: remover CoT-SC da lista de padrões (mover para roadmap); reescrever "Spring Boot 4.0.0 native integration" → "REST API e auto-configuração via Spring Boot; core framework-agnóstico"; corrigir "~15 MB JAR" com o número real medido na Fase 0.
- Documentar módulos sem docs: Agent Handoff, Invocation Queue, Provider Hub, Governance, Conversation Summarization, ComponentQueryRouter, Realtime (OpenAI).
- Consolidar fonte única de verdade: índice em `docs/` apontando RFC-005, SAC_AGENT_VALIDATION, compass, REDESIGN_PLAN com status de cada um (vivo/histórico).
- RFC-005: registrar decisão sobre `ExecutionContext` — ou migrar para record imutável (breaking) ou documentar o desvio e remover o `set()` deprecated do caminho multi-tenant.
- Atualizar REDESIGN_PLAN.md Fase 5 com status real (telas existem; falta Playwright).

## Fase 7 — Testes e QA (≈1 semana, contínua desde a Fase 1) 🟡

- Testcontainers (Postgres + Redis) para: repositórios JDBC novos (Fase 2), memória JDBC/Redis, pgvector, cache L2.
- Testes de segurança do marketplace (assinatura forjada, path traversal, permissões wildcard).
- Subir cobertura dos adapters langchain4j com menor ratio (memory-redis, memory-jdbc, vectorstores: hoje ~0.33).
- Playwright: fluxos admin (login, criação de tenant, impersonation, API keys) — pendência do REDESIGN_PLAN Fase 5.
- CI: gate de build + testes + `mvn dependency-check` (OWASP) + lint frontend.

## Fase 8 — Re-auditoria e gate final (1–2 dias) ✅

- Repetir a auditoria completa (mesmos 5 eixos: core, serviços, plugins, docs vs código, frontend) + `/security-review` no diff acumulado.
- Critério de saída ("Definition of Production-Ready"):
  - [ ] Zero achados críticos e altos abertos
  - [ ] Startup em profile prod falha sem persistência real configurada
  - [ ] Restart do serviço não perde fluxos/estado/conversas (teste E2E)
  - [ ] Logout invalida token; sem credenciais default em build prod
  - [ ] Extensão com assinatura inválida não instala
  - [ ] Plugin ausente gera erro explícito
  - [ ] readme sem claims falsos
  - [ ] Cobertura ≥ baseline +10pp nos módulos tocados

## Sequenciamento

```
Semana 1      : Fase 0 + Fase 1 (segurança crítica)
Semanas 2–3   : Fase 2 (persistência)        | Fase 5 (frontend, em paralelo)
Semana 4      : Fase 3 (plugins)             | Fase 5 (continuação)
Semanas 5–6   : Fase 4 (resiliência)         | Fase 7 (testes, contínuo)
Semana 6      : Fase 6 (docs)
Semana 7      : Fase 7 (fechamento QA) + Fase 8 (re-auditoria)
```

Riscos principais: (1) migração de persistência pode revelar acoplamentos ao comportamento in-memory — mitigar com Testcontainers desde o início; (2) reescrita do FlowPluginManager pode impactar fluxos existentes — cobrir com E2E antes de trocar; (3) strict mode no frontend pode gerar volume de erros — migração por diretório.
