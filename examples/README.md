# archflow Examples

Estado honesto de cada exemplo (auditoria de homologação, 2026-07):

| Exemplo | O que cobre | Estado |
|---------|-------------|--------|
| [`react/`](react/) | Web Component `<archflow-designer>` em React + invocação assíncrona de agente (`POST /archflow/agents/{id}/invoke`) + execução de workflow (`POST /api/workflows/{id}/execute`) | Demonstrativo. Endpoints são os reais da API; requer build local do `@archflow/component` (não publicado no npm) e backend em `:8080`. |
| [`vue/`](vue/) | O mesmo que `react/`, em Vue 3 | Demonstrativo. Mesmas ressalvas do exemplo React. |
| [`react-customer-support/`](react-customer-support/) | Cliente TypeScript da API REST (login JWT, listar workflows, executar, consultar execução) + UI Mantine | Demonstrativo. Endpoints reais (`/api/auth/login`, `/api/workflows`, `/api/executions`); requer build local do `@archflow/component`. |
| [`spring-boot-integration/`](spring-boot-integration/) | App Spring Boot que consome a API REST do archflow via `WebClient` (lista, executa e acompanha workflows) | Funcional contra um backend rodando. Não usa a API Java interna — só REST. |

## Removidos

- `spring-boot/` — removido na homologação: importava uma API Java que nunca
  existiu (`br.com.archflow.core.FlowEngine`, `LLMNode`, `archflow-spring-boot-starter`)
  e não compilava. Para embutir o archflow em um app Spring Boot hoje, use a API
  REST como em `spring-boot-integration/`.

## Notas gerais

- `@archflow/component` **não está publicado no npm**. Os exemplos frontend
  referenciam `file:../../archflow-ui`; rode antes:
  `cd ../../archflow-ui && npm install && npm run build:component`.
- Não existe endpoint de chat/stream síncrono de agente
  (`/api/agents/{id}/chat|stream` não existe). A invocação de agente é
  assíncrona: `POST /archflow/agents/{agentId}/invoke` responde
  `{ requestId, status: "accepted" }` e enfileira a execução.
