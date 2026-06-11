# Módulos internos sem documentação dedicada

> Referência curta dos módulos/subsistemas que existem no código mas não
> tinham documentação pública (apontado pela auditoria de 11/06/2026).
> Para cada um: o que é, classes-chave e como usar.

## Provider Hub (`archflow-langchain4j-provider-hub`)

Registry unificado para 15+ providers de LLM com troca em runtime.

- **Classes:** `LLMProviderHub` (singleton, cache de modelos com expiração,
  listeners de troca), `LLMProviderConfig` (builder), enum `LLMProvider`.
- **Uso:** obter/trocar o provider ativo por contexto de execução sem
  reconstruir adapters; descoberta via SPI (`ServiceLoader`).

## Agent Handoff (`archflow-agent` → `handoff/`)

Transferência peer-to-peer de conversas entre agentes com passagem de contexto.

- **Classes:** `AgentHandoffManager`.
- **Uso:** um agente delega a outro preservando o contexto da conversa —
  complementa o padrão Supervisor/Worker para fluxos não-hierárquicos.

## Agent Invocation Queue (`archflow-agent` → `queue/`)

Invocação assíncrona agente-a-agente sem bloqueio do chamador.

- **Classes:** `AgentInvocationQueue` (interface), `InMemoryAgentInvocationQueue`.
- **Produção:** a implementação in-memory perde mensagens no restart — o
  `ProductionReadinessGuard` falha o boot fora de dev/test se ela estiver
  ativa. Para produção, forneça um bean durável (fila persistente).

## Governance (`archflow-conversation` → `governance/`)

Regras de governança aplicadas ao loop conversacional (limites, políticas por
tenant). Configuração é documento JSON versionado — ver ADR-0001 (D3).

## Conversation Summarization (`archflow-conversation` → `summary/`)

Compressão de conversas longas para caber na janela de contexto.

- **Classes:** `DefaultConversationSummarizer` — usa função de sumarização
  LLM plugável com fallback extrativo determinístico quando o LLM falha.

## Component Query Router (`archflow-plugin-api` → `catalog/`)

Roteador descriptor-driven que escolhe o melhor componente para uma query em
linguagem natural.

- **Classes:** `ComponentQueryRouter`, `DefaultComponentQueryRouter`.
- **Ordem de matching:** keywords > capabilities > tags > texto livre,
  sobre os `ComponentMetadata` registrados no `ComponentCatalog`.

## Realtime / Voz (`archflow-langchain4j-realtime`)

SPI de sessões realtime (voz/streaming bidirecional) com implementação OpenAI.

- **Classes:** `RealtimeAdapter`/`RealtimeSession`/`RealtimeTransport` (SPI),
  `OpenAiRealtimeAdapter`, `OpenAiRealtimeSession`, `JdkWebSocketTransport`.
- **Uso:** sessões por (tenant, persona); o playground de voz da UI consome
  esta superfície. Outros providers (ex.: Gemini Live) entram pelo mesmo SPI.

---

## Fontes de verdade da documentação

| Documento | Status |
|-----------|--------|
| `docs/` (este diretório) | **Vivo** — referência principal |
| `docs/adr/` | **Vivo** — decisões arquiteturais numeradas |
| `documentos/PLANO_PRODUCAO.md` | **Vivo** — plano e status de prontidão para produção |
| `documentos/VendaX_RFC-005_v2_ArchFlow.md` | Histórico — requisitos de origem; desvios registrados em ADR (ver ADR-0004) |
| `documentos/SAC_AGENT_VALIDATION.md` | Histórico — validação pontual |
| `documentos/compass_artifact_*.md` | Histórico — pesquisa de mercado |
| `documentos/redesign/REDESIGN_PLAN.md` | Concluído — fases 1–4 entregues; tela admin entregue; Playwright pendente (Fase 7 do plano de produção) |
