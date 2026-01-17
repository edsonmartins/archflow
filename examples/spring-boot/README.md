# archflow Spring Boot Example

Exemplo completo de integração do archflow com Spring Boot 3.

## Funcionalidades

- Workflow de atendimento ao cliente
- Agent AI com ferramentas customizadas
- Streaming de respostas via SSE
- Métricas e observabilidade

## Executar

```bash
mvn spring-boot:run
```

Acesse: http://localhost:8080

## API Endpoints

```
POST   /api/workflows/customer-support/execute
GET    /api/agents/{id}/chat
GET    /api/agents/{id}/stream
```
