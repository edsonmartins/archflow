# Stack Tecnológico do archflow

## Backend

### Core
- **Java 17+**
  - Suporte a features modernas
  - Performance otimizada
  - Garbage collection aprimorado
  - Records e sealed classes

- **Spring Boot 3.x**
  - Web (REST API)
  - Security
  - Data JPA
  - Actuator
  - Native support

- **Apache Camel**
  - Orquestração de fluxos
  - Integração de sistemas
  - Error handling
  - Routing patterns

### AI/ML
- **LangChain4j**
  - Integração com LLMs
  - Chains e Agents
  - Memory management
  - Tools e Utilities

### Storage
- **PostgreSQL + pgvector**
  - Dados relacionais
  - Embeddings e vetores
  - Full-text search
  - JSONB para flexibilidade

- **Redis**
  - Cache distribuído
  - Pub/Sub
  - Rate limiting
  - Session storage

### Mensageria
- **Apache Kafka** (opcional)
  - Event streaming
  - Message queuing
  - Log aggregation
  - Stream processing

## Frontend

### Core
- **React + TypeScript**
  - Desenvolvimento robusto
  - Type safety
  - Componentização
  - Hooks

- **TailwindCSS**
  - Estilização flexível
  - Design system
  - Responsividade
  - Dark mode

### Componentes
- **React Flow**
  - Designer visual
  - Drag and drop
  - Customização
  - Interatividade

- **shadcn/ui**
  - Componentes base
  - Acessibilidade
  - Temas
  - Consistência

### State Management
- **Redux Toolkit**
  - Gerenciamento de estado
  - RTK Query
  - Redux DevTools
  - Middleware

## DevOps

### Containers
- **Docker**
  - Containerização
  - Multi-stage builds
  - Docker Compose
  - Networking

- **Kubernetes**
  - Orquestração
  - Auto-scaling
  - Service discovery
  - Load balancing

### CI/CD
- **GitHub Actions**
  - Build automation
  - Test execution
  - Deployment
  - Release management

### Monitoramento
- **Prometheus + Grafana**
  - Métricas
  - Alerting
  - Dashboards
  - PromQL

- **ELK Stack**
  - Log aggregation
  - Search
  - Visualization
  - Analysis

## Desenvolvimento

### Build
- **Maven**
  - Dependency management
  - Build lifecycle
  - Plugins
  - Multi-module

### Testes
- **JUnit 5**
  - Unit tests
  - Integration tests
  - Parameterized tests
  - Extensions

- **Mockito**
  - Mocking
  - Stubbing
  - Verification
  - Spy objects

### Quality
- **SonarQube**
  - Code quality
  - Security scanning
  - Coverage tracking
  - Technical debt

- **JaCoCo**
  - Code coverage
  - Branch coverage
  - Integration
  - Reports

## Requisitos de Sistema

### Desenvolvimento
- Java 17+
- Maven 3.8+
- Docker
- Node.js 18+

### Produção
- CPU: 4+ cores
- RAM: 8GB+
- Storage: 20GB+
- Network: 100Mbps+

## Configuração Recomendada

### JVM
```bash
JAVA_OPTS="-Xms2g -Xmx4g -XX:+UseG1GC"
```

### Kubernetes
```yaml
resources:
  requests:
    memory: "2Gi"
    cpu: "2"
  limits:
    memory: "4Gi"
    cpu: "4"
```

### PostgreSQL
```properties
max_connections = 200
shared_buffers = 1GB
effective_cache_size = 3GB
maintenance_work_mem = 256MB
```

### Redis
```properties
maxmemory 2gb
maxmemory-policy allkeys-lru
```

## Próximos Passos

- [Setup do Ambiente](setup.md)
- [Guia de Desenvolvimento](guidelines.md)
- [Configuração](../reference/configuration.md)
- [Deployment](../deployment/README.md)