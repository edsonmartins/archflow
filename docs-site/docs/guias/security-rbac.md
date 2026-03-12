---
title: "Seguranca e RBAC"
sidebar_position: 7
slug: security-rbac
---

# Seguranca e RBAC

Guia de configuracao de autenticacao, autorizacao e controle de acesso no archflow.

## Visao Geral

O archflow implementa seguranca em camadas:

```
┌─────────────────────────────────────────────────────┐
│                     Requisicao                       │
│                         │                            │
│                         ▼                            │
│  ┌──────────────────────────────────────────────┐   │
│  │  1. CORS Filter                               │   │
│  ├──────────────────────────────────────────────┤   │
│  │  2. JWT Authentication Filter                 │   │
│  │     - Valida token no header Authorization    │   │
│  │     - Extrai usuario e roles                  │   │
│  ├──────────────────────────────────────────────┤   │
│  │  3. API Key Authentication (alternativo)      │   │
│  │     - Header: X-API-Key                       │   │
│  ├──────────────────────────────────────────────┤   │
│  │  4. RBAC Authorization                        │   │
│  │     - @RequiresPermission verifica acesso     │   │
│  ├──────────────────────────────────────────────┤   │
│  │  5. Controller / Service                      │   │
│  └──────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────┘
```

## Autenticacao JWT

### Fluxo de autenticacao

```
Cliente                    Server
  │                          │
  │  POST /api/auth/login    │
  │  {username, password}    │
  │ ─────────────────────→   │
  │                          │  Valida credenciais
  │  {token, refreshToken}   │
  │ ←─────────────────────   │
  │                          │
  │  GET /api/workflows      │
  │  Authorization: Bearer   │
  │ ─────────────────────→   │
  │                          │  Valida JWT
  │  [workflows]             │  Extrai roles
  │ ←─────────────────────   │
  │                          │
  │  POST /api/auth/refresh  │
  │  {refreshToken}          │  (quando token expira)
  │ ─────────────────────→   │
  │                          │
  │  {token, refreshToken}   │  Novos tokens
  │ ←─────────────────────   │
```

### Login

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "admin",
    "password": "admin"
  }'
```

Resposta:

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "expiresIn": 900
}
```

### Usar o token

```bash
curl http://localhost:8080/api/workflows \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9..."
```

### Refresh token

Quando o access token expira (default: 15 minutos), use o refresh token:

```bash
curl -X POST http://localhost:8080/api/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{
    "refreshToken": "eyJhbGciOiJIUzI1NiJ9..."
  }'
```

### Configuracao JWT

| Propriedade | Default | Descricao |
|-------------|---------|-----------|
| `JWT_SECRET` | (obrigatorio) | Chave secreta HMAC-SHA256 (min 256 bits) |
| `JWT_EXPIRATION` | `900` | Expiracao do access token em segundos |
| `JWT_REFRESH_EXPIRATION` | `86400` | Expiracao do refresh token em segundos |
| `JWT_ISSUER` | `archflow` | Issuer do token |

:::caution
Em producao, gere uma chave segura: `openssl rand -base64 32`
:::

## Roles Padrao

O archflow define 4 roles com permissoes hierarquicas:

| Role | Descricao | Nivel |
|------|-----------|-------|
| `ADMIN` | Acesso total: usuarios, configuracao, seguranca | 4 |
| `DESIGNER` | Criar e editar workflows, plugins | 3 |
| `EXECUTOR` | Executar workflows e visualizar resultados | 2 |
| `VIEWER` | Apenas visualizacao (read-only) | 1 |

### Matriz de permissoes

| Recurso | VIEWER | EXECUTOR | DESIGNER | ADMIN |
|---------|--------|----------|----------|-------|
| Visualizar workflows | Sim | Sim | Sim | Sim |
| Executar workflows | - | Sim | Sim | Sim |
| Criar/editar workflows | - | - | Sim | Sim |
| Deletar workflows | - | - | Sim | Sim |
| Gerenciar plugins | - | - | Sim | Sim |
| Visualizar execucoes | Sim | Sim | Sim | Sim |
| Gerenciar API keys | - | Sim | Sim | Sim |
| Gerenciar usuarios | - | - | - | Sim |
| Configuracoes do sistema | - | - | - | Sim |
| Audit logs | - | - | - | Sim |

## Permissions Granulares

Alem das roles, o archflow suporta permissions granulares:

```
WORKFLOW_READ       - Visualizar workflows
WORKFLOW_CREATE     - Criar workflows
WORKFLOW_UPDATE     - Editar workflows
WORKFLOW_DELETE     - Deletar workflows
WORKFLOW_EXECUTE    - Executar workflows
EXECUTION_READ      - Visualizar execucoes
PLUGIN_READ         - Visualizar plugins
PLUGIN_MANAGE       - Instalar/remover plugins
APIKEY_READ         - Visualizar API keys
APIKEY_MANAGE       - Criar/revogar API keys
USER_READ           - Visualizar usuarios
USER_MANAGE         - Criar/editar/remover usuarios
SYSTEM_CONFIG       - Alterar configuracoes do sistema
AUDIT_READ          - Visualizar audit logs
```

## @RequiresPermission Annotation

Use a anotacao `@RequiresPermission` em controllers ou services para controle de acesso declarativo:

```java
@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    @GetMapping
    @RequiresPermission("WORKFLOW_READ")
    public List<WorkflowSummary> listWorkflows() {
        return workflowService.listAll();
    }

    @PostMapping
    @RequiresPermission("WORKFLOW_CREATE")
    public Workflow createWorkflow(@RequestBody WorkflowRequest request) {
        return workflowService.create(request);
    }

    @PostMapping("/{id}/execute")
    @RequiresPermission("WORKFLOW_EXECUTE")
    public ExecutionResponse executeWorkflow(
            @PathVariable String id,
            @RequestBody ExecutionRequest request) {
        return workflowService.execute(id, request);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("WORKFLOW_DELETE")
    public void deleteWorkflow(@PathVariable String id) {
        workflowService.delete(id);
    }
}
```

Voce pode combinar multiplas permissions:

```java
// Requer AMBAS as permissions
@RequiresPermission(value = {"WORKFLOW_UPDATE", "PLUGIN_MANAGE"}, mode = Mode.ALL)
public void updateWorkflowPlugins(...) { }

// Requer QUALQUER UMA das permissions
@RequiresPermission(value = {"ADMIN", "WORKFLOW_DELETE"}, mode = Mode.ANY)
public void deleteWorkflow(...) { }
```

## API Keys para Integracoes

API keys permitem que sistemas externos acessem o archflow sem login interativo.

### Criar uma API key

```bash
curl -X POST http://localhost:8080/api/apikeys \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "CI/CD Pipeline",
    "scopes": ["WORKFLOW_EXECUTE", "WORKFLOW_READ", "EXECUTION_READ"],
    "expiresInDays": 90
  }'
```

Resposta:

```json
{
  "id": "key-456",
  "name": "CI/CD Pipeline",
  "secret": "af_sk_abc123def456...",
  "scopes": ["WORKFLOW_EXECUTE", "WORKFLOW_READ", "EXECUTION_READ"],
  "expiresAt": "2026-06-10T00:00:00Z"
}
```

:::caution
O `secret` e retornado **apenas na criacao**. Armazene-o de forma segura (vault, secrets manager).
:::

### Usar a API key

```bash
curl http://localhost:8080/api/workflows \
  -H "X-API-Key: af_sk_abc123def456..."
```

### Scopes disponiveis

API keys usam os mesmos nomes de permission granular. A key so pode acessar recursos dentro dos scopes definidos.

### Gerenciar API keys

```bash
# Listar todas
curl http://localhost:8080/api/apikeys \
  -H "Authorization: Bearer $TOKEN"

# Revogar uma key
curl -X DELETE http://localhost:8080/api/apikeys/key-456 \
  -H "Authorization: Bearer $TOKEN"
```

## Configuracao CORS

Configure CORS no `application.yml` ou via variaveis de ambiente:

```yaml
# application.yml
archflow:
  security:
    cors:
      allowed-origins:
        - "http://localhost:5173"
        - "https://app.archflow.dev"
      allowed-methods:
        - GET
        - POST
        - PUT
        - DELETE
        - OPTIONS
      allowed-headers:
        - Authorization
        - Content-Type
        - X-API-Key
      allow-credentials: true
      max-age: 3600
```

Ou via variavel de ambiente:

```bash
ARCHFLOW_SECURITY_CORS_ALLOWED_ORIGINS=http://localhost:5173,https://app.archflow.dev
```

:::tip
Em desenvolvimento, o default permite `localhost:5173` (Vite dev server) e `localhost:8080`.
:::

## Best Practices de Seguranca

### 1. JWT Secret

```bash
# Gere uma chave forte
openssl rand -base64 32
# Resultado: kJ9v8mX3pQ7rT2wY5zA1bC4dE6fG8hI0jK2lM4nO6p=
```

Nunca commite o secret no repositorio. Use variaveis de ambiente ou secrets manager.

### 2. HTTPS em producao

Sempre use HTTPS em producao. Configure via reverse proxy (Nginx, Traefik) ou Kubernetes Ingress:

```yaml
# Kubernetes Ingress
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: archflow-ingress
  annotations:
    cert-manager.io/cluster-issuer: letsencrypt-prod
spec:
  tls:
    - hosts:
        - api.archflow.dev
      secretName: archflow-tls
  rules:
    - host: api.archflow.dev
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: archflow-service
                port:
                  number: 80
```

### 3. Rate limiting

Configure rate limiting para proteger contra abuso:

```yaml
archflow:
  security:
    rate-limit:
      enabled: true
      requests-per-minute: 60
      burst-size: 10
```

### 4. Audit logging

Ative audit logging para rastrear acoes criticas:

```yaml
archflow:
  security:
    audit:
      enabled: true
      log-level: INFO
      events:
        - LOGIN
        - LOGOUT
        - WORKFLOW_EXECUTE
        - WORKFLOW_DELETE
        - USER_CREATE
        - APIKEY_CREATE
        - APIKEY_REVOKE
```

### 5. Rotacao de tokens

- Access tokens devem expirar rapido (15 min default)
- Refresh tokens devem ter vida limitada (24h default)
- Implemente logout para invalidar tokens

### 6. Principio do menor privilegio

- Atribua a role minima necessaria
- Use API keys com scopes restritos
- Revise permissoes periodicamente

## Proximos passos

- [API Reference](../api/rest-endpoints) -- Endpoints de autenticacao e API keys
- [Deploy com Docker](./deploy-docker) -- Configurar variaveis de ambiente em producao
- [Troubleshooting](./troubleshooting) -- Resolver problemas de autenticacao
