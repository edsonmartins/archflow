# archflow Vue Example

Exemplo de integração do Web Component archflow com Vue 3.

## Funcionalidades

- Uso do `<archflow-designer>` em Vue
- Invocação assíncrona de agente via `POST /archflow/agents/{agentId}/invoke`
  (retorna `requestId` + `status: accepted` — a execução acontece em background;
  não há endpoint de chat/stream síncrono no backend hoje)
- Execução de workflow via `POST /api/workflows/{id}/execute`

## Pré-requisitos

O pacote `@archflow/component` **não está publicado no npm**. Ele é referenciado
via `file:../../archflow-ui`, então faça o build local do componente antes:

```bash
cd ../../archflow-ui
npm install          # ou pnpm install
npm run build:component
```

Também é necessário o backend archflow rodando em `http://localhost:8080`
(`docker compose up` na raiz do repositório).

## Instalar

```bash
npm install
```

## Executar

```bash
npm run dev
```

Acesse: http://localhost:5173

## Estado do exemplo

Este exemplo demonstra os endpoints reais da API, mas não é um app de produção:
o agente `customer-service` e o workflow `customer-support` precisam existir no
backend para as chamadas retornarem algo útil.
