---
title: "Deploy com Docker"
sidebar_position: 6
slug: deploy-docker
---

# Deploy com Docker e Kubernetes

Guia para empacotar e deployar o archflow com Docker e Kubernetes.

## Dockerfile Multi-Stage

O archflow usa um build multi-stage para gerar uma imagem otimizada:

```dockerfile
# ── Stage 1: Build ──
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Cache de dependencias Maven
COPY pom.xml .
COPY archflow-model/pom.xml archflow-model/
COPY archflow-core/pom.xml archflow-core/
COPY archflow-api/pom.xml archflow-api/
COPY archflow-agent/pom.xml archflow-agent/
COPY archflow-security/pom.xml archflow-security/
COPY archflow-plugin-api/pom.xml archflow-plugin-api/
COPY archflow-plugin-loader/pom.xml archflow-plugin-loader/
COPY archflow-plugins/pom.xml archflow-plugins/
COPY archflow-langchain4j/pom.xml archflow-langchain4j/
COPY archflow-observability/pom.xml archflow-observability/
COPY archflow-templates/pom.xml archflow-templates/
COPY archflow-conversation/pom.xml archflow-conversation/
COPY archflow-performance/pom.xml archflow-performance/
RUN mvn dependency:go-offline -B

# Build da aplicacao
COPY . .
RUN mvn clean package -DskipTests -B

# ── Stage 2: Frontend ──
FROM node:20-alpine AS frontend
WORKDIR /app
COPY archflow-ui/package*.json ./
RUN npm ci
COPY archflow-ui/ .
RUN npm run build

# ── Stage 3: Runtime ──
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Criar usuario nao-root
RUN addgroup -S archflow && adduser -S archflow -G archflow

# Copiar artefatos
COPY --from=build /app/archflow-agent/target/archflow-agent-*.jar app.jar
COPY --from=frontend /app/dist/ static/

# Configurar
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0"
ENV SPRING_PROFILES_ACTIVE=production

EXPOSE 8080

USER archflow

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

## docker-compose.yml

Composicao completa com aplicacao, PostgreSQL (pgvector) e Redis:

```yaml
version: '3.8'

services:
  app:
    build:
      context: .
      dockerfile: Dockerfile
    container_name: archflow-app
    ports:
      - "8080:8080"
    environment:
      JAVA_OPTS: "-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -Xms512m"
      SPRING_PROFILES_ACTIVE: production
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/archflow
      SPRING_DATASOURCE_USERNAME: archflow
      SPRING_DATASOURCE_PASSWORD: archflow
      SPRING_DATA_REDIS_HOST: redis
      SPRING_DATA_REDIS_PORT: 6379
      JWT_SECRET: "${JWT_SECRET:-change-me-in-production}"
      JWT_EXPIRATION: 900
      JWT_REFRESH_EXPIRATION: 86400
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 5s
      retries: 3
      start_period: 60s

  postgres:
    image: pgvector/pgvector:pg16
    container_name: archflow-postgres
    ports:
      - "5432:5432"
    environment:
      POSTGRES_DB: archflow
      POSTGRES_USER: archflow
      POSTGRES_PASSWORD: archflow
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U archflow"]
      interval: 10s
      timeout: 5s
      retries: 5

  redis:
    image: redis:7-alpine
    container_name: archflow-redis
    ports:
      - "6379:6379"
    volumes:
      - redis_data:/data
    command: redis-server --appendonly yes --maxmemory 256mb --maxmemory-policy allkeys-lru
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5

volumes:
  postgres_data:
  redis_data:
```

## Variaveis de Ambiente

| Variavel | Default | Descricao |
|----------|---------|-----------|
| `JAVA_OPTS` | `-Xms256m` | Opcoes da JVM |
| `SPRING_PROFILES_ACTIVE` | `dev` | Perfil Spring Boot |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/archflow` | URL do PostgreSQL |
| `SPRING_DATASOURCE_USERNAME` | `archflow` | Usuario do banco |
| `SPRING_DATASOURCE_PASSWORD` | `archflow` | Senha do banco |
| `SPRING_DATA_REDIS_HOST` | `localhost` | Host do Redis |
| `SPRING_DATA_REDIS_PORT` | `6379` | Porta do Redis |
| `JWT_SECRET` | (obrigatorio) | Chave secreta para JWT |
| `JWT_EXPIRATION` | `900` | Expiracao do access token (segundos) |
| `JWT_REFRESH_EXPIRATION` | `86400` | Expiracao do refresh token (segundos) |

:::caution
Em producao, **sempre** defina `JWT_SECRET` com um valor seguro (minimo 256 bits). Nunca use o valor default.
:::

## Build e Execucao Local

### Build da imagem

```bash
# Build completo
docker compose build

# Build com cache limpo
docker compose build --no-cache
```

### Executar

```bash
# Iniciar todos os servicos
docker compose up -d

# Verificar status
docker compose ps

# Ver logs
docker compose logs -f app

# Parar
docker compose down

# Parar e remover volumes (reset completo)
docker compose down -v
```

### Verificar saude

```bash
# Health check da aplicacao
curl http://localhost:8080/actuator/health

# Resposta esperada:
# {"status":"UP","components":{"db":{"status":"UP"},"redis":{"status":"UP"}}}
```

## Deploy no Kubernetes

### Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: archflow
  namespace: archflow
  labels:
    app: archflow
spec:
  replicas: 2
  selector:
    matchLabels:
      app: archflow
  template:
    metadata:
      labels:
        app: archflow
    spec:
      containers:
        - name: archflow
          image: ghcr.io/edsonmartins/archflow:latest
          ports:
            - containerPort: 8080
          env:
            - name: JAVA_OPTS
              value: "-XX:+UseG1GC -XX:MaxRAMPercentage=75.0"
            - name: SPRING_PROFILES_ACTIVE
              value: "production"
            - name: SPRING_DATASOURCE_URL
              valueFrom:
                secretKeyRef:
                  name: archflow-secrets
                  key: database-url
            - name: SPRING_DATASOURCE_USERNAME
              valueFrom:
                secretKeyRef:
                  name: archflow-secrets
                  key: database-username
            - name: SPRING_DATASOURCE_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: archflow-secrets
                  key: database-password
            - name: SPRING_DATA_REDIS_HOST
              value: "redis-service"
            - name: JWT_SECRET
              valueFrom:
                secretKeyRef:
                  name: archflow-secrets
                  key: jwt-secret
          resources:
            requests:
              memory: "512Mi"
              cpu: "250m"
            limits:
              memory: "2Gi"
              cpu: "1000m"
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            initialDelaySeconds: 60
            periodSeconds: 30
          startupProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            failureThreshold: 30
            periodSeconds: 10
```

### Service

```yaml
apiVersion: v1
kind: Service
metadata:
  name: archflow-service
  namespace: archflow
spec:
  selector:
    app: archflow
  ports:
    - protocol: TCP
      port: 80
      targetPort: 8080
  type: ClusterIP
```

### Secret

```bash
kubectl create namespace archflow

kubectl create secret generic archflow-secrets \
  --namespace archflow \
  --from-literal=database-url='jdbc:postgresql://postgres:5432/archflow' \
  --from-literal=database-username='archflow' \
  --from-literal=database-password='<senha-segura>' \
  --from-literal=jwt-secret='<chave-jwt-256bits>'
```

### Aplicar manifests

```bash
kubectl apply -f k8s/deployment.yaml
kubectl apply -f k8s/service.yaml

# Verificar status
kubectl get pods -n archflow
kubectl logs -f deployment/archflow -n archflow
```

## Health Checks

O archflow expoe endpoints de saude via Spring Boot Actuator:

| Endpoint | Descricao |
|----------|-----------|
| `/actuator/health` | Status geral (UP/DOWN) |
| `/actuator/health/liveness` | Liveness probe (aplicacao esta viva?) |
| `/actuator/health/readiness` | Readiness probe (pronta para receber trafego?) |
| `/actuator/info` | Informacoes da aplicacao (versao, build) |
| `/actuator/metrics` | Metricas Micrometer |

```bash
# Verificar saude completa
curl -s http://localhost:8080/actuator/health | jq .

# Saida esperada:
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP",
      "details": { "database": "PostgreSQL" }
    },
    "redis": {
      "status": "UP"
    },
    "diskSpace": {
      "status": "UP"
    }
  }
}
```

## Consideracoes de Scaling

### Horizontal scaling

O archflow e stateless por design (estado no PostgreSQL + Redis), permitindo escalamento horizontal:

```bash
# Kubernetes
kubectl scale deployment archflow --replicas=4 -n archflow

# Docker Compose
docker compose up -d --scale app=3
```

### Recomendacoes de recursos

| Carga | Replicas | CPU | Memoria | Redis |
|-------|----------|-----|---------|-------|
| Dev/Teste | 1 | 250m | 512Mi | 64MB |
| Producao (baixa) | 2 | 500m | 1Gi | 256MB |
| Producao (media) | 3-4 | 1000m | 2Gi | 512MB |
| Producao (alta) | 5+ | 2000m | 4Gi | 1GB+ |

### Dicas de performance

- **JVM**: Use `-XX:MaxRAMPercentage=75.0` para deixar 25% para o SO
- **Redis**: Configure `maxmemory-policy allkeys-lru` para evitar OOM
- **PostgreSQL**: Use connection pooling (HikariCP ja incluso no Spring Boot)
- **Plugins**: Plugins pesados podem exigir mais memoria -- monitore via `/actuator/metrics`

## Proximos passos

- [Quickstart Dev](./quickstart-dev) -- Desenvolvimento local
- [Seguranca e RBAC](./security-rbac) -- Configurar autenticacao e autorizacao
- [Troubleshooting](./troubleshooting) -- Resolver problemas comuns
