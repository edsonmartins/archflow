---
title: "Building Workflows"
sidebar_position: 2
slug: building-workflows
---

# Construindo Workflows

Guia prático para criar, configurar e executar workflows no archflow.

## Conceito de Workflow

Um **Workflow** no archflow é composto por três elementos fundamentais:

```
┌─────────────────────────────────────────────────────┐
│                     Workflow (Flow)                   │
│                                                       │
│   ┌─────────┐    connection    ┌─────────┐           │
│   │  Step 1  │ ──────────────→ │  Step 2  │          │
│   │ (ACTION) │                 │(DECISION)│          │
│   └─────────┘                  └─────────┘           │
│                                  │      │            │
│                          sim ────┘      └──── não    │
│                           ↓                   ↓      │
│                     ┌─────────┐         ┌─────────┐  │
│                     │  Step 3  │         │  Step 4  │ │
│                     │ (ACTION) │         │ (ACTION) │ │
│                     └─────────┘         └─────────┘  │
│                                                       │
│   Configuration: timeout, retryPolicy, parallel       │
└─────────────────────────────────────────────────────┘
```

| Elemento | Descrição |
|----------|-----------|
| **Flow** | Container principal com metadata (nome, versão, tags) |
| **Steps** | Nós do workflow — cada step executa uma operação via um componente AI |
| **Connections** | Arestas que definem o fluxo de execução entre steps |
| **Configuration** | Configurações globais (timeout, retry, paralelismo) |

## Tipos de Steps

O archflow suporta 5 tipos de step, cada um com comportamento específico:

| Tipo | Descrição | Uso típico |
|------|-----------|------------|
| `ACTION` | Executa uma operação simples | Chamar um assistente, processar texto |
| `DECISION` | Avalia uma condição e ramifica o fluxo | Classificar input, verificar threshold |
| `PARALLEL` | Executa múltiplos branches simultaneamente | Buscar em várias fontes ao mesmo tempo |
| `LOOP` | Repete uma sequência de steps até condição | Retry com refinamento, iteração sobre lista |
| `TOOL_CALL` | Invoca uma ferramenta específica | Buscar dados, chamar API externa |

### Step ACTION

O tipo mais comum. Executa um componente AI (Agent, Assistant ou Tool) com input/output:

```json
{
  "id": "step-analyze",
  "type": "ACTION",
  "componentId": "tech-support-assistant",
  "operation": "analyze",
  "configuration": {
    "model": "claude-3-sonnet",
    "temperature": 0.7,
    "maxTokens": 1024
  }
}
```

### Step DECISION

Avalia o resultado do step anterior e direciona o fluxo:

```json
{
  "id": "step-classify",
  "type": "DECISION",
  "componentId": "classifier-agent",
  "operation": "classify",
  "configuration": {
    "conditions": [
      {
        "expression": "result.category == 'technical'",
        "targetStepId": "step-tech-support"
      },
      {
        "expression": "result.category == 'billing'",
        "targetStepId": "step-billing"
      }
    ],
    "defaultTargetStepId": "step-general"
  }
}
```

### Step PARALLEL

Executa branches em paralelo e aguarda todos (ou o primeiro) completarem:

```json
{
  "id": "step-search",
  "type": "PARALLEL",
  "configuration": {
    "branches": [
      { "stepId": "step-search-docs" },
      { "stepId": "step-search-faq" },
      { "stepId": "step-search-tickets" }
    ],
    "waitStrategy": "ALL",
    "timeout": 10000
  }
}
```

### Step LOOP

Repete até uma condição ser satisfeita:

```json
{
  "id": "step-refine",
  "type": "LOOP",
  "componentId": "writing-assistant",
  "operation": "refine",
  "configuration": {
    "maxIterations": 5,
    "exitCondition": "result.quality >= 0.9"
  }
}
```

### Step TOOL_CALL

Invoca uma ferramenta diretamente:

```json
{
  "id": "step-calc",
  "type": "TOOL_CALL",
  "componentId": "calculator-tool",
  "operation": "calculate",
  "configuration": {
    "params": {
      "expression": "${input.formula}"
    }
  }
}
```

## Criando um Workflow via API REST

### 1. Autenticar

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "admin", "password": "admin"}' \
  | jq -r '.token')
```

### 2. Criar o workflow

```bash
curl -X POST http://localhost:8080/api/workflows \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "metadata": {
      "name": "Customer Support",
      "description": "Workflow de atendimento ao cliente com classificação e encaminhamento",
      "version": "1.0.0",
      "category": "support",
      "tags": ["ai", "support", "multi-agent"]
    },
    "steps": [
      {
        "id": "classifier",
        "type": "DECISION",
        "componentId": "classifier-agent",
        "operation": "classify",
        "configuration": {
          "model": "claude-3-haiku",
          "temperature": 0.3,
          "prompt": "Classifique o ticket do cliente como: technical, billing ou general"
        }
      },
      {
        "id": "tech-assistant",
        "type": "ACTION",
        "componentId": "tech-support-assistant",
        "operation": "analyze",
        "configuration": {
          "model": "claude-3-sonnet",
          "temperature": 0.7,
          "maxTokens": 2048
        }
      },
      {
        "id": "escalation",
        "type": "ACTION",
        "componentId": "escalation-tool",
        "operation": "escalate",
        "configuration": {
          "channel": "slack",
          "priority": "high"
        }
      }
    ],
    "configuration": {
      "timeout": 30000,
      "retryPolicy": {
        "maxAttempts": 3,
        "delay": 1000,
        "backoffMultiplier": 2.0
      }
    }
  }'
```

## Connections entre Steps

As connections definem como os steps se conectam:

```json
{
  "connections": [
    {
      "sourceId": "classifier",
      "targetId": "tech-assistant",
      "condition": "result.category == 'technical'",
      "isErrorPath": false
    },
    {
      "sourceId": "classifier",
      "targetId": "escalation",
      "condition": "result.category == 'billing'",
      "isErrorPath": false
    },
    {
      "sourceId": "tech-assistant",
      "targetId": "escalation",
      "isErrorPath": true
    }
  ]
}
```

| Campo | Tipo | Descrição |
|-------|------|-----------|
| `sourceId` | string | ID do step de origem |
| `targetId` | string | ID do step de destino |
| `condition` | string (opcional) | Expressão condicional para seguir esta connection |
| `isErrorPath` | boolean | Se `true`, esta connection só é seguida em caso de erro |

:::tip
Use `isErrorPath: true` para definir caminhos de fallback. Por exemplo, se o assistente falhar, encaminhe para escalação humana.
:::

## Retry Policies e Timeout

### Configuração global

Aplicada a todos os steps do workflow:

```json
{
  "configuration": {
    "timeout": 30000,
    "retryPolicy": {
      "maxAttempts": 3,
      "delay": 1000,
      "backoffMultiplier": 2.0,
      "retryableErrors": ["TIMEOUT", "RATE_LIMIT", "SERVICE_UNAVAILABLE"]
    }
  }
}
```

### Configuração por step

Cada step pode sobrescrever a política global:

```json
{
  "id": "step-critical",
  "type": "ACTION",
  "componentId": "payment-tool",
  "configuration": {
    "retryPolicy": {
      "maxAttempts": 5,
      "delay": 2000,
      "backoffMultiplier": 3.0
    },
    "timeout": 60000
  }
}
```

| Parâmetro | Default | Descrição |
|-----------|---------|-----------|
| `maxAttempts` | 3 | Número máximo de tentativas |
| `delay` | 1000 | Delay entre tentativas (ms) |
| `backoffMultiplier` | 2.0 | Multiplicador exponencial do delay |
| `retryableErrors` | todos | Lista de tipos de erro que permitem retry |
| `timeout` | 30000 | Tempo máximo de execução (ms) |

## Parallel Execution

Para executar steps em paralelo, use o tipo `PARALLEL` ou configure branches:

```json
{
  "id": "parallel-search",
  "type": "PARALLEL",
  "configuration": {
    "branches": [
      {
        "stepId": "search-knowledge-base",
        "weight": 0.5
      },
      {
        "stepId": "search-tickets",
        "weight": 0.3
      },
      {
        "stepId": "search-faq",
        "weight": 0.2
      }
    ],
    "waitStrategy": "ALL",
    "timeout": 15000,
    "mergeStrategy": "WEIGHTED_CONCAT"
  }
}
```

| Parâmetro | Opções | Descrição |
|-----------|--------|-----------|
| `waitStrategy` | `ALL`, `FIRST`, `MAJORITY` | Aguardar todos, o primeiro, ou maioria |
| `mergeStrategy` | `CONCAT`, `WEIGHTED_CONCAT`, `BEST` | Como combinar resultados |
| `timeout` | número (ms) | Timeout específico para o bloco paralelo |

## Exemplo Completo: Customer Support Workflow

Este exemplo demonstra um workflow de suporte ao cliente com 3 steps interligados:

```
  [Input do Cliente]
         │
         ▼
  ┌─────────────┐
  │  Classifier  │  ← Classifica o tipo de solicitação
  │  (DECISION)  │
  └─────────────┘
     │         │
  technical  billing/other
     │         │
     ▼         ▼
┌──────────┐  ┌──────────────┐
│ Assistant │  │  Escalation  │  ← Encaminha para humano
│ (ACTION)  │  │   (ACTION)   │
└──────────┘  └──────────────┘
     │ (error)        ▲
     └────────────────┘
```

### Workflow completo via API

```bash
curl -X POST http://localhost:8080/api/workflows \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "metadata": {
      "name": "Customer Support v2",
      "description": "Workflow completo: classificação → assistente → escalação",
      "version": "1.0.0",
      "category": "support",
      "tags": ["ai", "support"]
    },
    "steps": [
      {
        "id": "classifier",
        "type": "DECISION",
        "componentId": "classifier-agent",
        "operation": "classify",
        "connections": [
          {
            "sourceId": "classifier",
            "targetId": "assistant",
            "condition": "result.category == '\''technical'\''",
            "isErrorPath": false
          },
          {
            "sourceId": "classifier",
            "targetId": "escalation",
            "condition": "result.category != '\''technical'\''",
            "isErrorPath": false
          }
        ],
        "configuration": {
          "model": "claude-3-haiku",
          "temperature": 0.2,
          "prompt": "Classifique: technical ou other"
        }
      },
      {
        "id": "assistant",
        "type": "ACTION",
        "componentId": "tech-support-assistant",
        "operation": "analyze",
        "connections": [
          {
            "sourceId": "assistant",
            "targetId": "escalation",
            "isErrorPath": true
          }
        ],
        "configuration": {
          "model": "claude-3-sonnet",
          "temperature": 0.7,
          "maxTokens": 2048
        }
      },
      {
        "id": "escalation",
        "type": "ACTION",
        "componentId": "escalation-tool",
        "operation": "escalate",
        "connections": [],
        "configuration": {
          "channel": "slack",
          "priority": "high",
          "notifyTeam": true
        }
      }
    ],
    "configuration": {
      "timeout": 30000,
      "retryPolicy": {
        "maxAttempts": 3,
        "delay": 1000,
        "backoffMultiplier": 2.0
      }
    }
  }'
```

### Executar o workflow

```bash
curl -X POST http://localhost:8080/api/workflows/<workflow-id>/execute \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "input": "Meu servidor está retornando erro 503 desde ontem. Já tentei reiniciar mas o problema persiste."
  }'
```

### Verificar execução

```bash
curl http://localhost:8080/api/executions/<execution-id> \
  -H "Authorization: Bearer $TOKEN"
```

## Próximos passos

- [Custom Tools](./custom-tools) -- Criar ferramentas customizadas para seus workflows
- [Plugin Development](./plugin-development) -- Criar plugins completos
- [API Reference](../api/rest-endpoints) -- Todos os endpoints REST
- [Conceitos: Workflows](../conceitos/workflows) -- Teoria e arquitetura de workflows
