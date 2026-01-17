---
title: Observabilidade
sidebar_position: 3
slug: observabilidade
---

# Observabilidade

O archflow possui suporte nativo para métricas, tracing e logging estruturado.

## Métricas (Micrometer)

### Configuração

```yaml
archflow:
  observability:
    metrics:
      enabled: true
      export:
        prometheus:
          enabled: true
        datadog:
          enabled: false
```

### Dependências

```xml
<dependency>
    <groupId>br.com.archflow</groupId>
    <artifactId>archflow-observability</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Métricas Disponíveis

| Métrica | Tipo | Descrição |
|---------|------|-----------|
| `archflow.executions.total` | Counter | Total de execuções |
| `archflow.executions.duration` | Timer | Duração das execuções |
| `archflow.executions.errors` | Counter | Total de erros |
| `archflow.llm.tokens.total` | Counter | Tokens consumidos |
| `archflow.llm.calls.total` | Counter | Chamadas LLM |
| `archflow.tool.calls.total` | Counter | Chamadas de tools |
| `archflow.tool.calls.duration` | Timer | Duração de tools |

### Prometheus Endpoint

```bash
curl http://localhost:8080/actuator/prometheus

# Saída
archflow_executions_total{workflow="customer-support",status="success"} 1234.0
archflow_llm_tokens_total{model="gpt-4o",provider="openai"} 567890.0
archflow_tool_calls_duration_seconds{tool="customer_lookup",} 0.123
```

### Grafana Dashboard

```json
{
  "title": "Archflow Dashboard",
  "panels": [
    {
      "title": "Execuções por Workflow",
      "targets": [
        {
          "expr": "rate(archflow_executions_total[5m])"
        }
      ]
    },
    {
      "title": "Tokens Consumidos",
      "targets": [
        {
          "expr": "increase(archflow_llm_tokens_total[1h])"
        }
      ]
    },
    {
      "title": "Duração Média",
      "targets": [
        {
          "expr": "rate(archflow_executions_duration_sum[5m]) / rate(archflow_executions_duration_count[5m])"
        }
      ]
    }
  ]
}
```

## Tracing (OpenTelemetry)

### Configuração

```yaml
archflow:
  observability:
    tracing:
      enabled: true
      exporter:
        type: otlp
        endpoint: http://jaeger:4317
      sampling:
        probability: 0.1  # 10% sampling
```

### Spans Criados

Cada operação cria spans com contexto:

```
[workflow:customer-support] (123ms)
  ├─ [llm:classify] (45ms)
  ├─ [tool:customer_lookup] (23ms)
  ├─ [llm:generate_response] (55ms)
```

### Atributos de Span

| Atributo | Descrição |
|----------|-----------|
| `workflow.id` | ID do workflow |
| `workflow.name` | Nome do workflow |
| `node.id` | ID do nó executado |
| `agent.id` | ID do agente |
| `tool.name` | Nome da tool |
| `llm.model` | Modelo usado |
| `llm.tokens.input` | Tokens de entrada |
| `llm.tokens.output` | Tokens de saída |

### Jaeger Integration

```yaml
# docker-compose.yml
version: '3'
services:
  jaeger:
    image: jaegertracing/all-in-one:latest
    ports:
      - "5775:5775/udp"
      - "6831:6831/udp"
      - "6832:6832/udp"
      - "5778:5778"
      - "16686:16686"  # Jaeger UI
      - "14268:14268"
      - "14250:14250"
      - "9411:9411"
    environment:
      - COLLECTOR_ZIPKIN_HOST_PORT=:9411
```

## Logging Estruturado

### Configuração

```yaml
logging:
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} - %logger{36} - %msg"
  level:
    br.com.archflow: DEBUG
    br.com.archflow.execution: TRACE

archflow:
  observability:
    logging:
      structured: true  # JSON logging
      include-context: true
```

### Log Fields

```json
{
  "@timestamp": "2025-01-16T10:30:00.000Z",
  "level": "INFO",
  "logger": "br.com.archflow.execution.FlowEngine",
  "message": "Workflow execution completed",
  "workflow_id": "customer-support",
  "execution_id": "exec_123",
  "duration_ms": 1234,
  "status": "success",
  "mdc": {
    "userId": "user_456",
    "sessionId": "sess_789"
  }
}
```

### MDC (Mapped Diagnostic Context)

```java
@Component
public class ExecutionContextFilter {

    public void setUpContext(ExecutionContext context) {
        MDC.put("workflowId", context.getWorkflowId());
        MDC.put("executionId", context.getExecutionId());
        MDC.put("userId", context.getUserId());
    }

    public void clearContext() {
        MDC.clear();
    }
}
```

## Audit Log

### Configuração

```java
@Configuration
@EnableAudit
public class AuditConfig {

    @Bean
    public AuditLogger auditLogger(DataSource dataSource) {
        return JdbcAuditLogger.builder()
            .dataSource(dataSource)
            .table("audit_logs")
            .build();
    }
}
```

### Eventos Auditados

| Evento | Descrição |
|--------|-----------|
| `WORKFLOW_EXECUTED` | Workflow executado |
| `AGENT_CALLED` | Agente invocado |
| `TOOL_CALLED` | Tool executada |
| `LLM_CALLED` | LLM chamado |
| `ERROR_OCCURRED` | Erro ocorreu |
| `SUSPENSION_CREATED` | Conversação suspensa |
| `SUSPENSION_RESUMED` | Conversação resumida |

### Audit Record

```java
public interface AuditRepository extends JpaRepository<AuditRecord, Long> {

    List<AuditRecord> findByExecutionId(String executionId);

    List<AuditRecord> findByEventTypeAndTimestampBetween(
        String eventType,
        Instant start,
        Instant end
    );
}
```

## Health Checks

```bash
curl http://localhost:8080/actuator/health

# Response
{
  "status": "UP",
  "components": {
    "archflow": {
      "status": "UP",
      "details": {
        "workflows": 12,
        "agents": 5,
        "tools": 23
      }
    },
    "llm": {
      "status": "UP",
      "details": {
        "provider": "openai",
        "model": "gpt-4o"
      }
    }
  }
}
```

## Alertas

### Exemplo Prometheus Alert

```yaml
groups:
  - name: archflow
    rules:
      - alert: HighErrorRate
        expr: rate(archflow_executions_errors[5m]) > 0.05
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Alta taxa de erros no archflow"

      - alert: HighTokenUsage
        expr: increase(archflow_llm_tokens_total[1h]) > 100000
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "Alto consumo de tokens"
```

## Dashboard Completo

### Métricas Principais

1. **Execuções**: Volume e taxa de sucesso
2. **Performance**: Duração P50, P95, P99
3. **LLM**: Tokens, custo, latência
4. **Tools**: Taxa de sucesso, duração
5. **Erros**: Taxa e distribuição

### Queries Úteis

```promql
# Taxa de sucesso
rate(archflow_executions_total{status="success"}[5m]) / rate(archflow_executions_total[5m])

# Duração P95
histogram_quantile(0.95, rate(archflow_executions_duration_seconds_bucket[5m]))

# Custo estimado (OpenAI)
archflow_llm_tokens_total{model="gpt-4o"} * 0.000005
```
