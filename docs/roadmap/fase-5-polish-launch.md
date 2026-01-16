# Fase 5: Polish & Launch

**Duração Estimada**: 2-4 semanas (4 sprints)
**Objetivo**: Preparar para lançamento com performance, documentação e exemplos

---

## Visão Geral

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      Launch Preparation Checklist                       │
│                                                                          │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐   │
│  │ Performance │  │    Docs     │  │   Examples  │  │   Launch    │   │
│  │             │  │             │  │             │  │             │   │
│  │ - Caching   │  │ - Quickstart│  │ - React     │  │ - Release  │   │
│  │ - Parallel  │  │ - API Ref   │  │ - Vue       │  │ - Website  │   │
│  │ - Pooling   │  │ - Guides    │  │ - Spring    │  │ - Announce │   │
│  │ - Profiling │  │ - Videos    │  │ - Full App  │  │ - Metrics  │   │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘   │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Sprint 17: Performance

### Objetivo
Otimizar performance do sistema para produção.

### 17.1 Caching Strategy

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         Caching Architecture                            │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────────┐    │
│  │                       L1: In-Memory                             │    │
│  │                                                                  │    │
│  │  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────────┐│    │
│  │  │ Workflow   │  │   Agent    │  │   Tool     │  │ LLM Config ││    │
│  │  │ Definitions│  │  Configs   │  │  Results   │  │   Cache    ││    │
│  │  │ (Caffeine) │  │ (Caffeine) │  │ (Caffeine) │  │ (Caffeine) ││    │
│  │  └────────────┘  └────────────┘  └────────────┘  └────────────┘│    │
│  └────────────────────────────────────────────────────────────────┘    │
│                                   │                                     │
│                                   ▼                                     │
│  ┌────────────────────────────────────────────────────────────────┐    │
│  │                       L2: Redis                                 │    │
│  │                                                                  │    │
│  │  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────────┐│    │
│  │  │ Embedding  │  │ Vector     │  │ Session    │  │ Rate Limit ││    │
│  │  │   Cache    │  │   Cache    │  │   State    │  │   Counts   ││    │
│  │  └────────────┘  └────────────┘  └────────────┘  └────────────┘│    │
│  └────────────────────────────────────────────────────────────────┘    │
│                                   │                                     │
│                                   ▼                                     │
│  ┌────────────────────────────────────────────────────────────────┐    │
│  │                       L3: Database                              │    │
│  │                                                                  │    │
│  │  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────────┐│    │
│  │  │ PostgreSQL │  │   pgvector │  │ Audit Logs │  │ Metrics    ││    │
│  │  │  (Primary) │  │            │  │            │  │            ││    │
│  │  └────────────┘  └────────────┘  └────────────┘  └────────────┘│    │
│  └────────────────────────────────────────────────────────────────┘    │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### 17.2 Cache Configuration

```java
package org.archflow.cache.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableCaching
public class CacheConfiguration {

    /**
     * Cache manager local (L1) com Caffeine
     */
    @Bean
    public CacheManager caffeineCacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();

        Map<String, Caffeine<Object, Object>> cacheSpecs = new HashMap<>();

        // Workflow definitions - alteram raramente
        cacheSpecs.put("workflows", Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(java.time.Duration.ofHours(1))
            .recordStats());

        // Agent configs - alteram raramente
        cacheSpecs.put("agents", Caffeine.newBuilder()
            .maximumSize(1_000)
            .expireAfterWrite(java.time.Duration.ofMinutes(30))
            .recordStats());

        // Tool results - cache agressivo
        cacheSpecs.put("tool-results", Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(java.time.Duration.ofMinutes(10))
            .recordStats());

        // LLM responses - cache moderado
        cacheSpecs.put("llm-responses", Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterWrite(java.time.Duration.ofHours(24))
            .recordStats()
            .weigher((String key, Object value) -> {
                // Tamanho aproximado em bytes
                return key.length() + value.toString().length();
            })
            .maximumWeight(100_000_000)); // 100MB

        // Embeddings - cache muito agressivo
        cacheSpecs.put("embeddings", Caffeine.newBuilder()
            .maximumSize(1_000_000)
            .expireAfterWrite(java.time.Duration.ofDays(7))
            .recordStats());

        manager.setCustomCacheConfigs(cacheSpecs);
        return manager;
    }

    /**
     * Cache manager distribuído (L2) com Redis
     */
    @Bean
    public CacheManager redisCacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
            .serializeKeysWith(RedisSerializationContext.SerializationPair
                .fromSerializer(new StringRedisSerializer()))
            .serializeValuesWith(RedisSerializationContext.SerializationPair
                .fromSerializer(new GenericJackson2JsonRedisSerializer()))
            .entryTtl(Duration.ofMinutes(10));

        Map<String, RedisCacheConfiguration> cacheConfigs = new HashMap<>();

        // Session state - curta duração
        cacheConfigs.put("sessions", defaultConfig.entryTtl(Duration.ofMinutes(30)));

        // Rate limiting - curta duração
        cacheConfigs.put("rate-limits", defaultConfig.entryTtl(Duration.ofMinutes(1)));

        // Vector cache - média duração
        cacheConfigs.put("vectors", defaultConfig.entryTtl(Duration.ofHours(1)));

        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(defaultConfig)
            .withInitialCacheConfigurations(cacheConfigs)
            .build();
    }
}
```

### 17.3 Caching Annotations

```java
package org.archflow.workflow.service;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class WorkflowService {

    /**
     * Cache workflow definitions
     */
    @Cacheable(value = "workflows", key = "#workflowId")
    public WorkflowDefinition getWorkflow(String workflowId) {
        return workflowRepository.findById(workflowId)
            .orElseThrow(() -> new WorkflowNotFoundException(workflowId));
    }

    /**
     * Invalida cache ao atualizar
     */
    @CacheEvict(value = "workflows", key = "#workflow.id")
    public void updateWorkflow(WorkflowDefinition workflow) {
        workflowRepository.save(workflow);
    }

    /**
     * Cache embeddings com chave composta
     */
    @Cacheable(value = "embeddings",
               key = "#model + ':' + #text.hashCode()",
               cacheResolver = "redisCacheResolver")
    public float[] embed(String model, String text) {
        return embeddingModel.embed(text);
    }
}
```

### 17.4 Parallel Execution

```java
package org.archflow.execution.parallel;

import org.archflow.execution.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Executor de nós em paralelo
 */
@Component
public class ParallelNodeExecutor {

    private final ExecutorService executor;

    public ParallelNodeExecutor() {
        // Virtual threads para paralelismo leve
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Executa múltiplos nós em paralelo
     */
    public List<NodeExecutionResult> executeParallel(
            List<WorkflowNode> nodes,
            ExecutionContext context) {

        List<CompletableFuture<NodeExecutionResult>> futures = nodes.stream()
            .map(node -> executeAsync(node, context))
            .toList();

        // Aguarda todos completarem
        CompletableFuture<Void> allOf = CompletableFuture.allOf(
            futures.toArray(new CompletableFuture[0])
        );

        try {
            // Timeout global
            allOf.get(context.getTimeout(), TimeUnit.MILLISECONDS);

            return futures.stream()
                .map(CompletableFuture::join)
                .toList();

        } catch (TimeoutException e) {
            // Cancela pendentes
            futures.forEach(f -> f.cancel(true));
            throw new ExecutionTimeoutException("Parallel execution timeout");
        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            throw new ExecutionException("Parallel execution failed", e);
        }
    }

    private CompletableFuture<NodeExecutionResult> executeAsync(
            WorkflowNode node,
            ExecutionContext context) {

        return CompletableFuture.supplyAsync(() -> {
            return nodeExecutor.execute(node, context);
        }, executor);
    }

    /**
     * Execução paralela com threshold
     */
    public List<NodeExecutionResult> executeParallelIfMoreThan(
            List<WorkflowNode> nodes,
            ExecutionContext context,
            int threshold) {

        if (nodes.size() < threshold) {
            // Executa sequencialmente
            return nodes.stream()
                .map(node -> nodeExecutor.execute(node, context))
                .toList();
        }

        // Executa em paralelo
        return executeParallel(nodes, context);
    }
}
```

### 17.5 Connection Pooling

```java
package org.archflow.infrastructure.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
@EnableJpaRepositories(basePackages = "org.archflow.**.repository")
public class DatabaseConfiguration {

    @Bean
    @ConfigurationProperties(prefix = "archflow.datasource.hikari")
    public HikariConfig hikariConfig() {
        HikariConfig config = new HikariConfig();

        // Pool size
        config.setMaximumPoolSize(20);
        config.setMinimumIdle(5);

        // Timeout
        config.setConnectionTimeout(30000);      // 30s
        config.setIdleTimeout(600000);           // 10min
        config.setMaxLifetime(1800000);          // 30min

        // Performance
        config.setAutoCommit(true);
        config.setReadOnly(false);
        config.setConnectionTestQuery("SELECT 1");

        return config;
    }

    @Bean
    public DataSource dataSource(HikariConfig config) {
        return new HikariDataSource(config);
    }
}
```

```yaml
# application.yml
archflow:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
      connection-test-query: "SELECT 1"
      pool-name: "archflow-pool"

      # Métricas
      register-mbeans: true

  # HTTP client pooling
  http:
    max-connections: 100
    max-connections-per-route: 20
    connection-ttl: 60s
    connection-request-timeout: 10s
```

### 17.6 Performance Benchmarks

```java
package org.archflow.performance;

import org.archflow.execution.*;
import org.archflow.model.*;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;

/**
 * Benchmarks de performance
 */
@Component
public class PerformanceBenchmarker {

    private final WorkflowEngine workflowEngine;

    public BenchmarkResult benchmarkWorkflow(String workflowId,
                                              BenchmarkConfig config) {

        // Warmup
        for (int i = 0; i < config.warmupIterations(); i++) {
            workflowEngine.execute(workflowId, Map.of("test", "data"));
        }

        // Benchmark
        List<Long> durations = new CopyOnWriteArrayList<>();

        ExecutorService executor = Executors.newFixedThreadPool(config.concurrency());

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < config.iterations(); i++) {
            Future<?> future = executor.submit(() -> {
                Instant start = Instant.now();
                workflowEngine.execute(workflowId, Map.of("test", "data"));
                durations.add(Duration.between(start, Instant.now()).toMillis());
            });
            futures.add(future);
        }

        // Aguarda conclusão
        for (Future<?> future : futures) {
            try {
                future.get(config.timeout(), TimeUnit.SECONDS);
            } catch (Exception e) {
                // Erro
            }
        }

        executor.shutdown();

        // Calcula estatísticas
        return calculateStatistics(durations);
    }

    private BenchmarkResult calculateStatistics(List<Long> durations) {
        durations.sort(Long::compareTo);

        long min = durations.get(0);
        long max = durations.get(durations.size() - 1);
        double avg = durations.stream().mapToLong(Long::longValue).average().orElse(0);

        long p50 = durations.get((int) (durations.size() * 0.5));
        long p95 = durations.get((int) (durations.size() * 0.95));
        long p99 = durations.get((int) (durations.size() * 0.99));

        return new BenchmarkResult(
            min, max, avg, p50, p95, p99,
            durations.size(),
            config.concurrency()
        );
    }

    public record BenchmarkConfig(
        int iterations,
        int warmupIterations,
        int concurrency,
        int timeout
    ) {
        public static BenchmarkConfig standard() {
            return new BenchmarkConfig(1000, 100, 10, 60);
        }
    }

    public record BenchmarkResult(
        long minMs,
        long maxMs,
        double avgMs,
        long p50Ms,
        long p95Ms,
        long p99Ms,
        int totalIterations,
        int concurrency
    ) {}
}
```

---

## Sprint 18: DX & Documentation

### Objetivo
Criar documentação completa para desenvolvedores.

### 18.1 Documentation Structure

```
docs/
├── README.md                          # Overview
├── getting-started/
│   ├── installation.md               # Instalação
│   ├── quickstart.md                 # Hello World em 5 min
│   └── first-workflow.md             # Primeiro workflow
├── concepts/
│   ├── workflows.md                  # Conceitos de workflow
│   ├── agents.md                     # Conceitos de agentes
│   ├── tools.md                      # Conceitos de tools
│   └── memory.md                     # Conceitos de memória
├── guides/
│   ├── building-workflows.md         # Criando workflows
│   ├── custom-tools.md               # Tools customizadas
│   ├── rag-implementation.md         # Implementando RAG
│   ├── agents-supervisor.md          # Padrão supervisor
│   └── streaming-responses.md        # Streaming
├── api/
│   ├── rest-api.md                   # API REST
│   ├── java-api.md                   # API Java
│   └── web-component-api.md          # Web Component API
├── deployment/
│   ├── docker-deployment.md          # Docker
│   ├── kubernetes-deployment.md      # Kubernetes
│   └── cloud-deployment.md           # Nuvens
├── enterprise/
│   ├── security.md                   # Segurança
│   ├── observability.md              # Observabilidade
│   └── scaling.md                    # Escalabilidade
└── reference/
    ├── configuration.md              # Configuração
    ├── cli.md                        # CLI
    └── troubleshooting.md            # Troubleshooting
```

### 18.2 Quickstart Guide

```markdown
# archflow Quickstart

Get started with archflow in 5 minutes.

## Prerequisites

- Java 17+
- Maven 3.9+
- Docker (for local development)

## Installation

### Option 1: Spring Boot Starter

Add to your `pom.xml`:

\`\`\`xml
<dependency>
    <groupId>org.archflow</groupId>
    <artifactId>archflow-spring-boot-starter</artifactId>
    <version>2.0.0</version>
</dependency>
\`\`\`

### Option 2: Docker

\`\`\`bash
docker run -d \\
  -p 8080:8080 \\
  -e ARCHFLOW_API_KEY=your-key-here \\
  archflow/server:2.0.0
\`\`\`

## Your First Workflow

Create a simple chat workflow:

\`\`\`java
@RestController
public class ChatController {

    private final WorkflowEngine workflowEngine;

    @PostMapping("/chat")
    public String chat(@RequestBody String message) {
        return workflowEngine.execute("simple-chat", Map.of(
            "message", message
        ));
    }
}
\`\`\`

Define the workflow:

\`\`\`yaml
# workflows/simple-chat.yaml
workflow:
  id: simple-chat
  name: "Simple Chat"

  nodes:
    - id: input
      type: InputNode
      config:
        schema:
          message: string

    - id: llm
      type: LLMNode
      config:
        model: openai/gpt-4o
        system: "You are a helpful assistant."

    - id: output
      type: OutputNode

  edges:
    - from: input
      to: llm
    - from: llm
      to: output
\`\`\`

## Next Steps

- [Building Workflows](./guides/building-workflows.md)
- [Custom Tools](./guides/custom-tools.md)
- [REST API Reference](./api/rest-api.md)
```

### 18.3 API Reference Generator

```java
package org.archflow.docs;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Gera documentação de API a partir de anotações
 */
public class ApiDocGenerator {

    public String generateRestApiDocs(Class<?> controllerClass) {
        StringBuilder docs = new StringBuilder();

        RestController controller = controllerClass.getAnnotation(RestController.class);
        RequestMapping classMapping = controllerClass.getAnnotation(RequestMapping.class);

        docs.append("## ").append(controllerClass.getSimpleName()).append("\n\n");

        for (Method method : controllerClass.getDeclaredMethods()) {
            GetMapping get = method.getAnnotation(GetMapping.class);
            PostMapping post = method.getAnnotation(PostMapping.class);
            PutMapping put = method.getAnnotation(Putting.class);
            DeleteMapping delete = method.getAnnotation(DeleteMapping.class);

            if (get != null) {
                docs.append(generateEndpointDocs("GET", get.value(), get.path(), method));
            } else if (post != null) {
                docs.append(generateEndpointDocs("POST", post.value(), post.path(), method));
            }
            // ... outros métodos
        }

        return docs.toString();
    }

    private String generateEndpointDocs(String httpMethod,
                                         String[] value,
                                         String[] path,
                                         Method method) {
        StringBuilder docs = new StringBuilder();

        String fullPath = value.length > 0 ? value[0] : (path.length > 0 ? path[0] : "");

        docs.append("### ").append(httpMethod).append(" ").append(fullPath).append("\n\n");

        RequiresPermission perm = method.getAnnotation(RequiresPermission.class);
        if (perm != null) {
            docs.append("**Permission:** `").append(perm.value()).append("`\n\n");
        }

        // Parâmetros
        docs.append("**Parameters:**\n\n");
        // ... extrai parâmetros

        // Response
        docs.append("**Response:**\n\n");
        docs.append("```json\n");
        // ... extrai tipo de retorno
        docs.append("\n```\n\n");

        return docs.toString();
    }
}
```

### 18.4 Web Component API Docs

```typescript
/**
 * archflow-designer Web Component API
 *
 * @element archflow-designer
 *
 * @example
 * ```html
 * <archflow-designer
 *   workflow-id="customer-support"
 *   api-base="http://localhost:8080/api"
 *   theme="dark">
 * </archflow-designer>
 * ```
 */

export interface ArchflowDesignerAttributes {
  /**
   * ID do workflow para carregar/editar
   */
  'workflow-id'?: string;

  /**
   * URL base da API do archflow
   * @default "http://localhost:8080/api"
   */
  'api-base'?: string;

  /**
   * Tema visual
   * @default "light"
   */
  'theme'?: 'light' | 'dark';

  /**
   * Modo somente leitura
   * @default false
   */
  'readonly'?: boolean;

  /**
   * Mostra minimapa
   * @default true
   */
  'show-minimap'?: boolean;

  /**
   * Grade de snap
   * @default true
   */
  'snap-to-grid'?: boolean;
}

export interface ArchflowDesignerEvents extends Map<string, any> {
  /**
   * Disparado quando workflow é salvo
   */
  'workflow-saved': CustomEvent<WorkflowSavedDetail>;

  /**
   * Disparado quando workflow é executado
   */
  'workflow-executed': CustomEvent<WorkflowExecutedDetail>;

  /**
   * Disparado quando há erro de validação
   */
  'validation-error': CustomEvent<ValidationErrorDetail>;

  /**
   * Disparado quando nó é selecionado
   */
  'node-selected': CustomEvent<NodeSelectedDetail>;
}

export interface WorkflowSavedDetail {
  workflowId: string;
  workflow: WorkflowDefinition;
}

export interface WorkflowExecutedDetail {
  executionId: string;
  result: Record<string, unknown>;
  duration: number;
}

export interface ValidationErrorDetail {
  errors: ValidationError[];
}

export interface NodeSelectedDetail {
  nodeId: string;
  node: WorkflowNode;
}
```

---

## Sprint 19: Examples

### Objetivo
Criar exemplos completos em diferentes frameworks.

### 19.1 React Example

```
examples/react-customer-support/
├── package.json
├── src/
│   ├── App.tsx
│   ├── main.tsx
│   ├── components/
│   │   └── ArchflowDesigner.tsx
│   └── vite-env.d.ts
├── index.html
├── tsconfig.json
└── vite.config.ts
```

```tsx
// src/App.tsx
import { useState, useRef } from 'react';
import './App.css';

// Web Component precisa ser declarado
declare global {
  namespace JSX {
    interface IntrinsicElements {
      'archflow-designer': ArchflowDesignerAttributes;
    }
  }
}

interface ArchflowDesignerAttributes extends React.HTMLAttributes<HTMLElement> {
  'workflow-id'?: string;
  'api-base'?: string;
  'theme'?: 'light' | 'dark';
  'readonly'?: boolean;
  onWorkflowSaved?: (event: CustomEvent) => void;
  onWorkflowExecuted?: (event: CustomEvent) => void;
}

function App() {
  const [workflowId, setWorkflowId] = useState<string>('customer-support');
  const [executionResult, setExecutionResult] = useState<any>(null);
  const designerRef = useRef<any>(null);

  const handleSave = (event: CustomEvent) => {
    const detail = event.detail;
    console.log('Workflow saved:', detail);
  };

  const handleExecute = (event: CustomEvent) => {
    const detail = event.detail;
    setExecutionResult(detail.result);
  };

  const handleNewWorkflow = () => {
    setWorkflowId('');
  };

  return (
    <div className="app">
      <header>
        <h1>Customer Support Workflow</h1>
        <button onClick={handleNewWorkflow}>New Workflow</button>
      </header>

      <main>
        <archflow-designer
          ref={designerRef}
          workflow-id={workflowId}
          api-base="http://localhost:8080/api"
          theme="dark"
          onWorkflowSaved={handleSave}
          onWorkflowExecuted={handleExecute}
        />
      </main>

      {executionResult && (
        <aside className="result-panel">
          <h2>Execution Result</h2>
          <pre>{JSON.stringify(executionResult, null, 2)}</pre>
        </aside>
      )}
    </div>
  );
}

export default App;
```

### 19.2 Vue Example

```vue
<!-- src/App.vue -->
<template>
  <div class="app">
    <header>
      <h1>Document Processing Workflow</h1>
      <button @click="newWorkflow">New Workflow</button>
    </header>

    <main>
      <archflow-designer
        :workflow-id="workflowId"
        :api-base="apiBase"
        :theme="theme"
        @workflow-saved="handleSave"
        @workflow-executed="handleExecute"
      />
    </main>

    <aside v-if="executionResult" class="result-panel">
      <h2>Execution Result</h2>
      <pre>{{ JSON.stringify(executionResult, null, 2) }}</pre>
    </aside>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';

const workflowId = ref('document-processor');
const apiBase = ref('http://localhost:8080/api');
const theme = ref<'light' | 'dark'>('dark');
const executionResult = ref<any>(null);

const handleSave = (event: CustomEvent) => {
  console.log('Workflow saved:', event.detail);
};

const handleExecute = (event: CustomEvent) => {
  executionResult.value = event.detail.result;
};

const newWorkflow = () => {
  workflowId.value = '';
};
</script>

<style scoped>
.app {
  display: grid;
  grid-template-rows: auto 1fr;
  height: 100vh;
}

header {
  padding: 1rem;
  background: #1a1a1a;
  color: white;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

main {
  flex: 1;
}

.result-panel {
  position: fixed;
  right: 0;
  top: 60px;
  width: 400px;
  height: calc(100vh - 60px);
  background: #2a2a2a;
  padding: 1rem;
  overflow: auto;
}

pre {
  color: #0f0;
  font-size: 12px;
}
</style>
```

### 19.3 Spring Boot Integration

```java
package org.archflow.example;

import org.archflow.annotation.ArchflowWorkflow;
import org.archflow.annotation.ArchflowInput;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.*;

/**
 * Exemplo de integração Spring Boot
 */
@SpringBootApplication
public class ArchflowExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArchflowExampleApplication.class, args);
    }
}

@RestController
@RequestMapping("/api")
public class WorkflowController {

    private final WorkflowEngine workflowEngine;

    /**
     * Endpoint simples que executa workflow
     */
    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        Map<String, Object> input = Map.of(
            "message", request.message(),
            "conversationId", request.conversationId()
        );

        WorkflowResult result = workflowEngine.execute("customer-support-chat", input);

        return new ChatResponse(
            result.getOutput("response"),
            result.getExecutionId()
        );
    }

    /**
     * Endpoint com streaming
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestParam String message) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);

        workflowEngine.executeStream("customer-support-chat", Map.of("message", message))
            .doOnNext(chunk -> {
                try {
                    emitter.send(SseEmitter.event()
                        .name("message")
                        .data(chunk));
                } catch (IOException e) {
                    emitter.completeWithError(e);
                }
            })
            .doOnComplete(emitter::complete)
            .doOnError(emitter::completeWithError)
            .subscribe();

        return emitter;
    }

    public record ChatRequest(String message, String conversationId) {}
    public record ChatResponse(String response, String executionId) {}
}
```

### 19.4 Full Stack Example

```
examples/full-stack-ecommerce-support/
├── backend/
│   ├── pom.xml
│   └── src/main/java/
│       └── org/archflow/example/ecommerce/
│           ├── EcommerceSupportApplication.java
│           ├── config/
│           │   └── ArchflowConfiguration.java
│           ├── controller/
│           │   ├── SupportController.java
│           │   └── OrderController.java
│           └── workflow/
│               └── workflows/
│                   ├── order-status.yaml
│                   └── product-recommendation.yaml
├── frontend/
│   ├── package.json
│   └── src/
│       ├── App.tsx
│       ├── pages/
│       │   ├── SupportPage.tsx
│       │   └── OrderTrackingPage.tsx
│       └── components/
│           └── ChatInterface.tsx
└── docker-compose.yml
```

```yaml
# docker-compose.yml
version: '3.8'

services:
  backend:
    build: ./backend
    ports:
      - "8080:8080"
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://db:5432/archflow
      SPRING_REDIS_HOST: redis
    depends_on:
      - db
      - redis

  frontend:
    build: ./frontend
    ports:
      - "3000:80"
    environment:
      VITE_API_BASE: http://localhost:8080/api

  db:
    image: pgvector/pgvector:pg16
    environment:
      POSTGRES_DB: archflow
      POSTGRES_USER: archflow
      POSTGRES_PASSWORD: archflow
    volumes:
      - db-data:/var/lib/postgresql/data

  redis:
    image: redis:7-alpine
    volumes:
      - redis-data:/data

volumes:
  db-data:
  redis-data:
```

---

## Sprint 20: Launch

### Objetivo
Lançar versão 1.0.0 oficial.

### 20.1 Release Checklist

```markdown
# Release 1.0.0 Checklist

## Pre-Release

### Code
- [ ] Todos os sprints completados
- [ ] Testes passando (coverage > 80%)
- [ ] Sem bugs críticos abertos
- [ ] Dependências atualizadas
- [ ] Sem warnings de depreciação

### Documentation
- [ ] README atualizado
- [ ] Quickstart funcional
- [ ] API reference completa
- [ ] Guia de instalação
- [ ] Guia de troubleshooting
- [ ] Changelog preparado

### Infrastructure
- [ ] CI/CD configurado
- [ ] Docker images publicadas
- [ ] Releases no GitHub
- [ ] Website atualizado
- [ ] Monitoring configurado

### Security
- [ ] Audit de segurança
- [ ] Dependencies scan
- [ ] Segredos removidos
- [ ] RBAC testado

## Release Day

### GitHub Release
- [ ] Tag criada (v1.0.0)
- [ ] Release notes publicadas
- [ ] Assets anexados (jars, docker images)
- [ ] Anúncio no Discussions

### Website
- [ ] Homepage atualizada
- [ ] Blog post publicado
- [ ] Documentação publicada
- [ ] Examples adicionados

### Community
- [ ] Post no LinkedIn
- [ ] Post no Twitter/X
- [ ] Email para lista de discussão
- [ ] Anúncio em comunidades Java
- [ ] Anúncio em comunidades AI

## Post-Release

### Monitoring
- [ ] Monitorar erros (primeiras 24h)
- [ ] Monitorar performance
- [ ] Coletar feedback
- [ ] Responder issues/prs

### Metrics
- [ ] GitHub stars
- [ ] Downloads
- [ ] Clones do repositório
- [ ] Tráfego no website
- [ ] Signups para newsletter
```

### 20.2 Release Notes Template

```markdown
# archflow 1.0.0 Release Notes

## Overview

archflow 1.0.0 é o primeiro lançamento estável da primeira plataforma Java-Nativa para construção visual de workflows de IA.

## What's New

### 🎨 Visual Workflow Designer
- Web Component que funciona em qualquer framework (React, Vue, Angular, Svelte)
- Drag-and-drop para criar workflows visualmente
- 15+ tipos de nodes nativos
- Preview em tempo real

### 🔧 Java-Native Core
- Baseado em LangChain4j 1.10.0
- Spring Boot 3.x integration
- Suporte a 15+ provedores LLM
- MCP (Model Context Protocol) nativo

### 🏢 Enterprise Features
- RBAC (Role-Based Access Control)
- Audit logging completo
- Observabilidade (Metrics, Tracing)
- API Keys para autenticação programática

### 🔄 Advanced Features
- Suspend/Resume conversations
- Workflow-as-Tool pattern
- Extension Marketplace
- Multi-provider LLM Hub

## Installation

### Maven
\`\`\`xml
<dependency>
    <groupId>org.archflow</groupId>
    <artifactId>archflow-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
\`\`\`

### Docker
\`\`\`bash
docker run -d -p 8080:8080 archflow/server:1.0.0
\`\`\`

### Web Component
\`\`\`bash
npm install @archflow/component
\`\`\`

## Documentation

- [Getting Started](https://archflow.org/docs/getting-started)
- [API Reference](https://archflow.org/docs/api)
- [Examples](https://github.com/archflow/archflow-examples)

## What's Next

Roadmap para 1.1.0:
- [ ] Enhanced debugging UI
- [ ] Workflow versioning
- [ ] A/B testing para prompts
- [ ] CLI tool

## Contributors

Thank you to all contributors!

## Support

- GitHub: https://github.com/archflow/archflow
- Discord: https://discord.gg/archflow
- Email: support@archflow.org
```

### 20.3 Launch Announcements

```markdown
# LinkedIn Post Template

🚀 EXCITING NEWS! I'm thrilled to announce the launch of archflow 1.0.0!

archflow is the first Java-native visual AI platform - think "LangFlow for the Java world."

🤔 Why archflow?

78% of CIOs cite compliance as a barrier to AI adoption.
For enterprise Java teams, Python-based solutions don't integrate well.

archflow solves this by providing:
✅ Java-native (Spring Boot 3)
✅ Visual workflow designer
✅ Web Component UI (works in React, Vue, Angular...)
✅ MCP integration
✅ Enterprise features from day one

🔗 Try it out: https://archflow.org
📖 Docs: https://archflow.org/docs
💻 GitHub: https://github.com/archflow/archflow

#Java #AI #LangChain #EnterpriseAI #OpenSource
```

```markdown
# Twitter/X Post Thread

1/ 🚀 We're launching archflow 1.0.0 today!

archflow = LangFlow for the Java world.

A visual AI builder that's:
- Java-native (Spring Boot)
- Framework-agnostic (Web Component)
- Enterprise-ready
- MCP-compatible

Here's why we built it 🧵

2/ The problem:

Most AI workflow tools are Python-based. But:
- 70% of enterprise apps run on JVM
- 47% of fintechs use Java primarily
- Python solutions don't integrate well with Java systems

3/ So we built archflow:

✅ Drag-and-drop workflow designer
✅ 15+ LLM providers
✅ Runs as Spring Boot app
✅ Web Component UI - works in ANY frontend framework
✅ RBAC, audit, observability

4/ The Web Component is the game-changer:

```html
<archflow-designer
  workflow-id="my-workflow"
  api-base="http://localhost:8080"
  theme="dark">
</archflow-designer>
```

Works in React, Vue, Angular, Svelte, vanilla JS. Zero lock-in.

5/ Check it out:
🌐 https://archflow.org
💻 https://github.com/archflow/archflow
📖 https://archflow.org/docs

#Java #AI #OSS
```

### 20.4 Demo Scripts

```markdown
# Demo Script for Launch Video (5 minutes)

## Intro (0:30)
- "Hi, I'm [name], creator of archflow"
- "archflow is the first Java-native visual AI platform"
- "Think LangFlow meets Spring Boot"
- "Let me show you what it can do"

## Problem (0:45)
- "Most AI tools are Python-based"
- "But enterprise runs on Java"
- "Integrating Python solutions is painful"
- "archflow is built from the ground up for Java teams"

## Solution - Web Component (1:30)
- "Our secret sauce: Web Component UI"
- Demo: Show component in React app
- Demo: Same component in Vue app
- "Zero lock-in, works anywhere"

## Demo - Building a Workflow (1:30)
- "Let's build a customer support workflow"
- Show drag-and-drop designer
- Add LLM node, knowledge base node
- Execute and show result
- "That's it - deployed as Spring Boot app"

## Enterprise (0:30)
- "We didn't forget enterprise needs"
- Show RBAC, audit logs
- Show metrics dashboard
- "Production-ready from day one"

## Outro (0:30)
- "Available today at archflow.org"
- "Open source, Apache 2.0"
- "Star us on GitHub, join our Discord"
- "Let's build the future of Java AI together!"
```

---

## Critérios de Sucesso da Fase 5

- [ ] Performance: workflow execution < 100ms (p95)
- [ ] Caching reduzindo calls LLM em > 50%
- [ ] Documentação completa publicada
- [ ] 3+ exemplos funcionais
- [ ] Docker images publicadas
- [ ] GitHub release criado
- [ ] Website atualizado
- [ ] Anúncios públicos feitos
- [ ] Primeiros 100 GitHub stars
- [ ] Comunidade ativa no Discord

---

## Resumo do Roadmap Completo

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    archflow 2.0 Roadmap Summary                        │
│                                                                          │
│  Fase 1: Foundation (4-6 semanas)                                       │
│  ├─ LangChain4j 1.10.0 upgrade                                          │
│  ├─ Tool Interceptor Chain                                             │
│  ├─ toolCallId Tracking System                                         │
│  ├─ Streaming Protocol (SSE)                                           │
│  └─ MCP Integration                                                    │
│                                                                          │
│  Fase 2: Visual Experience (6-8 semanas)                               │
│  ├─ Web Component Core                                                 │
│  ├─ Node Registry com 15+ tipos                                       │
│  ├─ Canvas com drag-and-drop                                          │
│  └─ Workflow Execution via component                                   │
│                                                                          │
│  Fase 3: Enterprise Capabilities (4-6 semanas)                         │
│  ├─ Auth & RBAC                                                        │
│  ├─ Observabilidade (Metrics, Tracing, Audit)                          │
│  ├─ Func-Agent Mode (determinístico)                                  │
│  └─ Multi-LLM Provider Hub                                             │
│                                                                          │
│  Fase 4: Ecosystem (4-6 semanas)                                       │
│  ├─ Workflow Templates                                                 │
│  ├─ Suspend/Resume Conversations                                       │
│  ├─ Extension Marketplace                                             │
│  └─ Workflow-as-Tool Pattern                                          │
│                                                                          │
│  Fase 5: Polish & Launch (2-4 semanas)                                 │
│  ├─ Performance (Caching, Parallel, Pooling)                           │
│  ├─ Documentação completa                                              │
│  ├─ Exemplos em múltiplos frameworks                                  │
│  └─ Lançamento 1.0.0                                                   │
│                                                                          │
│  Total: 20-30 semanas (5-7 meses)                                       │
│                                                                          │
│  Diferencial Único:                                                     │
│  "Primeiro Visual AI Builder Java-Nativo distribuído como Web Component"│
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Parabéns! 🎉

Ao completar estas 5 fases, o archflow estará posicionado como:

1. **ÚNICO**: Java-native + Web Component UI + MCP + Enterprise
2. **DISRUPTIVO**: Zero frontend lock-in com Web Component
3. **COMPETITIVO**: Feature-parity com soluções Python
4. **EMPRESA**: Pronto para produção desde day one

**O LangFlow para o mundo Java!**
