---
title: REST API Reference
sidebar_position: 0
slug: rest-endpoints
---

# REST API Reference

Referência completa dos endpoints REST do archflow.

**Base URL:** `http://localhost:8080/api`

Todos os endpoints (exceto login) requerem autenticação via Bearer token no header `Authorization`.

---

## Autenticação

### POST /auth/login

Autentica um usuário e retorna tokens JWT.

**Request:**
```json
{
  "username": "string",
  "password": "string"
}
```

**Response (200):**
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "expiresIn": 900
}
```

**Errors:**
- `401` — Credenciais inválidas

---

### POST /auth/refresh

Renova o access token usando o refresh token.

**Request:**
```json
{
  "refreshToken": "string"
}
```

**Response (200):**
```json
{
  "token": "novo-access-token",
  "refreshToken": "novo-refresh-token"
}
```

**Errors:**
- `401` — Refresh token inválido ou expirado

---

### POST /auth/logout

Invalida os tokens do usuário atual.

**Headers:** `Authorization: Bearer <token>`

**Response:** `204 No Content`

---

### GET /auth/me

Retorna informações do usuário autenticado.

**Headers:** `Authorization: Bearer <token>`

**Response (200):**
```json
{
  "id": "user-123",
  "username": "admin",
  "name": "Administrator",
  "roles": ["ADMIN"]
}
```

---

## API Keys

### POST /apikeys

Cria uma nova API key.

**Request:**
```json
{
  "name": "My Integration Key",
  "scopes": ["WORKFLOW_EXECUTE", "WORKFLOW_READ"],
  "expiresInDays": 90
}
```

**Response (201):**
```json
{
  "id": "key-456",
  "name": "My Integration Key",
  "secret": "af_sk_abc123...",
  "scopes": ["WORKFLOW_EXECUTE", "WORKFLOW_READ"],
  "expiresAt": "2026-06-10T00:00:00Z"
}
```

:::caution
O campo `secret` é retornado **apenas na criação**. Armazene-o de forma segura.
:::

---

### GET /apikeys

Lista todas as API keys do usuário atual.

**Response (200):**
```json
[
  {
    "id": "key-456",
    "name": "My Integration Key",
    "scopes": ["WORKFLOW_EXECUTE", "WORKFLOW_READ"],
    "createdAt": "2026-03-12T10:00:00Z",
    "expiresAt": "2026-06-10T00:00:00Z",
    "lastUsedAt": "2026-03-12T15:30:00Z",
    "active": true
  }
]
```

---

### GET /apikeys/{id}

Retorna detalhes de uma API key específica.

**Response (200):** Mesmo formato de item da listagem.

**Errors:**
- `404` — API key não encontrada

---

### DELETE /apikeys/{id}

Revoga (desativa) uma API key.

**Response:** `204 No Content`

**Errors:**
- `404` — API key não encontrada

---

## Workflows

### GET /workflows

Lista todos os workflows acessíveis pelo usuário.

**Response (200):**
```json
[
  {
    "id": "flow-789",
    "name": "Customer Support",
    "description": "Multi-agent customer support workflow",
    "version": "1.0.0",
    "status": "active",
    "updatedAt": "2026-03-12T10:00:00Z",
    "stepCount": 5
  }
]
```

---

### GET /workflows/{id}

Retorna o workflow completo com steps e configuração.

**Response (200):**
```json
{
  "id": "flow-789",
  "metadata": {
    "name": "Customer Support",
    "description": "Multi-agent customer support workflow",
    "version": "1.0.0",
    "category": "support",
    "tags": ["ai", "support"]
  },
  "steps": [
    {
      "id": "step-1",
      "type": "ASSISTANT",
      "componentId": "tech-support-assistant",
      "operation": "analyze",
      "connections": [
        {
          "sourceId": "step-1",
          "targetId": "step-2",
          "isErrorPath": false
        }
      ],
      "configuration": {
        "model": "claude-3-sonnet",
        "temperature": 0.7
      }
    }
  ],
  "configuration": {
    "timeout": 30000,
    "retryPolicy": {
      "maxAttempts": 3,
      "delay": 1000
    }
  }
}
```

---

### POST /workflows

Cria um novo workflow.

**Request:** Mesmo formato do response de `GET /workflows/{id}` (sem `id`).

**Response (201):** Workflow criado com `id` gerado.

---

### PUT /workflows/{id}

Atualiza um workflow existente.

**Request:** Mesmo formato da criação.

**Response (200):** Workflow atualizado.

---

### DELETE /workflows/{id}

Remove um workflow.

**Response:** `204 No Content`

---

### POST /workflows/{id}/execute

Executa um workflow com input opcional.

**Request:**
```json
{
  "input": "Hello, I need help with my account"
}
```

**Response (202):**
```json
{
  "executionId": "exec-abc123",
  "status": "RUNNING"
}
```

---

## Executions

### GET /executions

Lista execuções. Filtro opcional por workflow.

**Query params:**
- `workflowId` (optional) — Filtrar por workflow

**Response (200):**
```json
[
  {
    "id": "exec-abc123",
    "workflowId": "flow-789",
    "workflowName": "Customer Support",
    "status": "COMPLETED",
    "startedAt": "2026-03-12T15:30:00Z",
    "completedAt": "2026-03-12T15:30:05Z",
    "duration": 5000,
    "error": null
  }
]
```

---

### GET /executions/{id}

Retorna detalhes de uma execução específica.

**Response (200):** Mesmo formato de item da listagem, com dados adicionais de output e métricas.

---

## Status Codes

| Code | Descrição |
|------|-----------|
| `200` | Sucesso |
| `201` | Recurso criado |
| `202` | Aceito (execução assíncrona) |
| `204` | Sem conteúdo (delete/logout) |
| `400` | Request inválido |
| `401` | Não autenticado |
| `403` | Sem permissão |
| `404` | Recurso não encontrado |
| `409` | Conflito (ex: workflow em execução) |
| `500` | Erro interno |

## Error Response

Todos os erros seguem o formato:

```json
{
  "error": "ERROR_CODE",
  "message": "Human-readable error message",
  "timestamp": "2026-03-12T15:30:00Z",
  "path": "/api/workflows/missing-id"
}
```
