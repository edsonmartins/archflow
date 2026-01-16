# Fase 1: Foundation - Especificação Detalhada

**Duração**: 4-6 semanas
**Objetivo**: Base técnica sólida com features disruptivas
**Status**: Planejamento

---

## Overview

Esta fase estabelece os alicerces técnicos do archflow 2.0. Focamos em upgrade do LangChain4j, implementação de interceptors para tools, sistema de tracking hierárquico (toolCallId), protocolo de streaming e integração MCP.

### Deliverables

- [ ] LangChain4j 1.0.0-beta1 → 1.10.0
- [ ] Tool Interceptor Chain
- [ ] toolCallId Tracking System
- [ ] ArchflowEvent Streaming Protocol
- [ ] MCP Server/Client

---

## Sprint 1: Upgrade LangChain4j (1 semana)

### Objetivo

Atualizar a integração com LangChain4j da versão 1.0.0-beta1 para 1.10.0, resolvendo breaking changes e aproveitando novas features.

### Breaking Changes Conhecidos

| API Antiga | API Nova | Ação |
|------------|-----------|------|
| `ChatLanguageModel.generate(messages)` | Removido | Migrar para `generate(List<Message>)` |
| `Tokenizer` | `TokenCountEstimator` | Renomear todas as referências |
| `@Subagent` annotation | Removida (1.5.0+) | Usar novo Agentic API |
| `ChatMemory.memoryId()` | Alterado | Atualizar assinatura |

### Estrutura de Módulos

```
archflow-langchain4j/
├── archflow-langchain4j-core/
│   ├── pom.xml (atualizar langchain4j.version)
│   └── src/main/java/
│       └── br/com/archflow/langchain4j/
│           ├── adapter/
│           │   ├── LangChainAdapter.java (manter)
│           │   └── LangChainAdapterFactory.java (manter)
│           ├── config/
│           │   ├── LangChain4jProperties.java (atualizar)
│           │   └── LangChain4jAutoConfiguration.java (revisar)
│           └── model/
│               ├── ChatModelAdapter.java (atualizar)
│               └── EmbeddingModelAdapter.java (manter)
│
├── archflow-langchain4j-openai/
│   ├── pom.xml (atualizar para 1.10.0)
│   └── src/main/java/
│       └── br/com/archflow/langchain4j/openai/
│           ├── OpenAiChatAdapter.java
│           │   ├── ATUALIZAR: streaming support
│           │   ├── ATUALIZAR: new model names (gpt-4.1, o1)
│           │   └── ATUALIZAR: ImageVision support
│           └── OpenAiEmbeddingAdapter.java
│
├── archflow-langchain4j-anthropic/
│   ├── pom.xml
│   └── src/main/java/
│       └── br/com/archflow/langchain4j/anthropic/
│           ├── AnthropicChatAdapter.java (COMPLETAR)
│           │   ├── IMPLEMENTAR: chat básico
│           │   ├── IMPLEMENTAR: streaming
│           │   └── IMPLEMENTAR: claude-3.5-sonnet, claude-3.7-sonnet
│           └── AnthropicEmbeddingAdapter.java
│
├── archflow-langchain4j-mcp/ (NOVO)
│   ├── pom.xml
│   └── src/main/java/
│       └── br/com/archflow/langchain4j/mcp/
│           ├── server/ArchflowMCPServer.java
│           ├── client/ArchflowMCPClient.java
│           └── transport/MCPTransport.java
│
├── archflow-langchain4j-streaming/ (NOVO)
│   ├── pom.xml
│   └── src/main/java/
│       └── br/com/archflow/langchain4j/streaming/
│           ├── StreamingChatAdapter.java
│           └── ArchflowSSEEmitter.java
│
└── archflow-langchain4j-spring-ai/ (NOVO)
    ├── pom.xml
    └── src/main/java/
        └── br/com/archflow/langchain4j/springai/
            ├── SpringAIChatModelAdapter.java
            └── SpringAIProperties.java
```

### Especificação: Atualização de Versão

**Arquivo**: `pom.xml` (root)

```xml
<properties>
    <langchain4j.version>1.0.0-beta1</langchain4j.version>  <!-- ANTES -->
    <langchain4j.version>1.10.0</langchain4j.version>      <!-- DEPOIS -->
</properties>
```

### Especificação: OpenAiChatAdapter Atualizado

**Arquivo**: `archflow-langchain4j-openai/src/main/java/.../OpenAiChatAdapter.java`

```java
package br.com.archflow.langchain4j.openai;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel.Builder;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

public class OpenAiChatAdapter implements LangChainAdapter {

    @Override
    public void configure(Map<String, Object> properties) {
        String apiKey = (String) properties.get("apiKey");
        String modelName = (String) properties.getOrDefault("modelName", "gpt-4.1");
        String baseUrl = (String) properties.get("baseUrl");
        Double temperature = (Double) properties.get("temperature");
        Integer maxTokens = (Integer) properties.get("maxTokens");
        Boolean enableStreaming = (Boolean) properties.getOrDefault("enableStreaming", true);

        Builder builder = OpenAiChatModel.builder()
            .apiKey(apiKey)
            .modelName(modelName)
            .temperature(temperature)
            .maxTokens(maxTokens);

        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
        }

        this.chatModel = builder.build();

        // Streaming support
        if (enableStreaming) {
            OpenAiStreamingChatModel.Builder streamingBuilder = OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature)
                .maxTokens(maxTokens);

            if (baseUrl != null) {
                streamingBuilder.baseUrl(baseUrl);
            }

            this.streamingModel = streamingBuilder.build();
        }
    }

    @Override
    public Object execute(String operation, Object input, ExecutionContext context) {
        return switch (operation) {
            case "generate", "chat" -> generate(input, context);
            case "stream" -> stream(input, context);
            default -> throw new UnsupportedOperationException("Unknown operation: " + operation);
        };
    }

    private String generate(Object input, ExecutionContext context) {
        // Implementação com nova API
        return chatModel.generate(convertToMessages(input)).content();
    }

    private Flux<String> stream(Object input, ExecutionContext context) {
        // Implementação streaming
        return streamingModel.generate(convertToMessages(input), flux -> {
            // Callback para cada chunk
        });
    }
}
```

### Critérios de Aceite

- [ ] Compila sem erros com LangChain4j 1.10.0
- [ ] Todos os testes passam
- [ ] OpenAiChatAdapter suporta streaming
- [ ] AnthropicChatAdapter implementado (básico)
- [ ] Documentação atualizada

---

## Sprint 2: Tool Interceptor + toolCallId (1.5 semanas)

### Objetivo

Implementar sistema de interceptors para tools e tracking hierárquico de execução.

### Especificação: Tool Interceptor Chain

**Arquivo**: `archflow-core/src/main/java/.../tool/interceptor/ToolInterceptor.java`

```java
package br.com.archflow.core.tool.interceptor;

/**
 * Interface para interceptores de execução de tools.
 * Permite monitoring, caching, validação e outras cross-cutting concerns.
 */
public interface ToolInterceptor {

    /**
     * Executado antes da execução da tool.
     * Útil para: logging, pré-validação, cache check
     */
    void beforeExecute(ToolInterceptorContext context);

    /**
     * Executado após execução bem-sucedida.
     * Útil para: logging, pós-processamento, cache populate
     */
    void afterExecute(ToolInterceptorContext context, ToolResult result);

    /**
     * Executado em caso de erro.
     * Útil para: error logging, fallback, retry decision
     */
    void onError(ToolInterceptorContext context, Exception error);

    /**
     * Ordem de execução. Menor = executa primeiro.
     */
    default int getOrder() {
        return 100;
    }

    /**
     * Se true, exceções lançadas neste interceptor interrompem a cadeia.
     */
    default boolean stopOnError() {
        return false;
    }
}
```

**Arquivo**: `archflow-core/src/main/java/.../tool/interceptor/ToolInterceptorContext.java`

```java
package br.com.archflow.core.tool.interceptor;

import java.time.Instant;
import java.util.Map;

/**
 * Contexto compartilhado entre interceptors.
 */
public class ToolInterceptorContext {

    private final String executionId;          // toolCallId
    private final String parentExecutionId;    // Para hierarquia
    private final Tool tool;
    private final ToolInput input;
    private final Instant startTime;
    private final Map<String, Object> metadata;

    // Getters e setters para valores computados
    private Object cachedResult;
    private Boolean shouldCache;
    private Boolean shouldSkip;

    public ToolInterceptorContext(
            String executionId,
            String parentExecutionId,
            Tool tool,
            ToolInput input) {
        this.executionId = executionId;
        this.parentExecutionId = parentExecutionId;
        this.tool = tool;
        this.input = input;
        this.startTime = Instant.now();
        this.metadata = new HashMap<>();
    }

    public String getExecutionId() { return executionId; }
    public String getParentExecutionId() { return parentExecutionId; }
    public Tool getTool() { return tool; }
    public ToolInput getInput() { return input; }
    public Instant getStartTime() { return startTime; }

    public long getDurationMillis() {
        return Instant.now().toEpochMilli() - startTime.toEpochMilli();
    }

    public void putMetadata(String key, Object value) {
        metadata.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getMetadata(String key) {
        return (T) metadata.get(key);
    }
}
```

### Implementações de Interceptors

**Arquivo**: `archflow-core/src/main/java/.../tool/interceptor/impl/LoggingInterceptor.java`

```java
package br.com.archflow.core.tool.interceptor.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class LoggingInterceptor implements ToolInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LoggingInterceptor.class);

    @Override
    public void beforeExecute(ToolInterceptorContext context) {
        log.info("[Tool:{}] Starting execution - executionId: {}",
            context.getTool().getName(),
            context.getExecutionId()
        );
    }

    @Override
    public void afterExecute(ToolInterceptorContext context, ToolResult result) {
        log.info("[Tool:{}] Completed in {}ms - executionId: {}",
            context.getTool().getName(),
            context.getDurationMillis(),
            context.getExecutionId()
        );
    }

    @Override
    public void onError(ToolInterceptorContext context, Exception error) {
        log.error("[Tool:{}] Failed after {}ms - executionId: {}",
            context.getTool().getName(),
            context.getDurationMillis(),
            context.getExecutionId(),
            error
        );
    }

    @Override
    public int getOrder() {
        return Integer.MIN_VALUE; // Executa primeiro
    }
}
```

**Arquivo**: `archflow-core/src/main/java/.../tool/interceptor/impl/CachingInterceptor.java`

```java
package br.com.archflow.core.tool.interceptor.impl;

@Component
public class CachingInterceptor implements ToolInterceptor {

    private final Cache<String, ToolResult> cache;

    public CachingInterceptor() {
        this.cache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(1000)
            .build();
    }

    @Override
    public void beforeExecute(ToolInterceptorContext context) {
        String cacheKey = buildCacheKey(context);

        ToolResult cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            context.putMetadata("_cached", true);
            context.setCachedResult(cached);
            context.setShouldSkip(true);
        }
    }

    @Override
    public void afterExecute(ToolInterceptorContext context, ToolResult result) {
        if (Boolean.TRUE.equals(context.getShouldCache())) {
            String cacheKey = buildCacheKey(context);
            cache.put(cacheKey, result);
        }
    }

    private String buildCacheKey(ToolInterceptorContext context) {
        return context.getTool().getName() + ":"
            + context.getInput().hashCode();
    }

    @Override
    public int getOrder() {
        return 10; // Executa cedo
    }
}
```

**Arquivo**: `archflow-core/src/main/java/.../tool/interceptor/impl/MetricsInterceptor.java`

```java
package br.com.archflow.core.tool.interceptor.impl;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Component
public class MetricsInterceptor implements ToolInterceptor {

    private final MeterRegistry meterRegistry;

    @Override
    public void beforeExecute(ToolInterceptorContext context) {
        // Timer.Sample captura o start time
        Timer.Sample sample = Timer.start(meterRegistry);
        context.putMetadata("_timerSample", sample);
    }

    @Override
    public void afterExecute(ToolInterceptorContext context, ToolResult result) {
        Timer.Sample sample = context.getMetadata("_timerSample");
        if (sample != null) {
            sample.stop(Timer.builder("archflow.tool.execution")
                .tag("tool", context.getTool().getName())
                .tag("status", "success")
                .register(meterRegistry)
            );
        }
    }

    @Override
    public void onError(ToolInterceptorContext context, Exception error) {
        Timer.Sample sample = context.getMetadata("_timerSample");
        if (sample != null) {
            sample.stop(Timer.builder("archflow.tool.execution")
                .tag("tool", context.getTool().getName())
                .tag("status", "error")
                .tag("error", error.getClass().getSimpleName())
                .register(meterRegistry)
            );
        }
    }
}
```

**Arquivo**: `archflow-core/src/main/java/.../tool/interceptor/impl/GuardrailsInterceptor.java`

```java
package br.com.archflow.core.tool.interceptor.impl;

@Component
public class GuardrailsInterceptor implements ToolInterceptor {

    private final List<Guardrail> guardrails;

    public GuardrailsInterceptor() {
        this.guardrails = List.of(
            new PIIGuardrail(),
            new ToxicContentGuardrail(),
            new RateLimitGuardrail()
        );
    }

    @Override
    public void beforeExecute(ToolInterceptorContext context) {
        ToolInput input = context.getInput();

        for (Guardrail guardrail : guardrails) {
            GuardrailResult result = guardrail.validate(input);
            if (!result.isAllowed()) {
                throw new GuardrailViolationException(
                    "Tool execution blocked by guardrail: " + result.getReason()
                );
            }
        }
    }

    @Override
    public int getOrder() {
        return 20; // Após logging e cache
    }
}
```

### Especificação: Tool Executor com Interceptor Chain

**Arquivo**: `archflow-core/src/main/java/.../tool/ToolExecutor.java`

```java
package br.com.archflow.core.tool;

import br.com.archflow.core.tool.interceptor.ToolInterceptor;
import br.com.archflow.core.tool.interceptor.ToolInterceptorContext;

@Component
public class ToolExecutor {

    private final List<ToolInterceptor> interceptors;

    public ToolExecutor(List<ToolInterceptor> interceptors) {
        // Ordena por ordem de execução
        this.interceptors = interceptors.stream()
            .sorted(Comparator.comparingInt(ToolInterceptor::getOrder))
            .toList();
    }

    public ToolResult execute(Tool tool, ToolInput input) {
        return execute(tool, input, null);
    }

    public ToolResult execute(Tool tool, ToolInput input, String parentExecutionId) {

        // Gera toolCallId único
        String executionId = generateExecutionId();

        // Cria contexto
        ToolInterceptorContext context = new ToolInterceptorContext(
            executionId,
            parentExecutionId,
            tool,
            input
        );

        // Fase: Before
        for (ToolInterceptor interceptor : interceptors) {
            try {
                interceptor.beforeExecute(context);
            } catch (Exception e) {
                if (interceptor.stopOnError()) {
                    throw e;
                }
            }

            if (Boolean.TRUE.equals(context.isShouldSkip())) {
                return context.getCachedResult();
            }
        }

        // Execução atual
        ToolResult result;
        try {
            result = tool.execute(input);

            // Fase: After (sucesso)
            for (ToolInterceptor interceptor : interceptors) {
                try {
                    interceptor.afterExecute(context, result);
                } catch (Exception e) {
                    if (interceptor.stopOnError()) {
                        throw e;
                    }
                }
            }

        } catch (Exception e) {
            // Fase: Error
            for (ToolInterceptor interceptor : interceptors) {
                try {
                    interceptor.onError(context, e);
                } catch (Exception suppressed) {
                    // Log e continua
                }
            }
            throw e;
        }

        return result;
    }

    private String generateExecutionId() {
        return "exec_" + UUID.randomUUID().toString().substring(0, 8);
    }
}
```

### Especificação: toolCallId Tracking System

**Arquivo**: `archflow-model/src/main/java/.../execution/ExecutionId.java`

```java
package br.com.archflow.model.execution;

import java.util.Objects;

/**
 * Identificador único de execução com suporte a hierarquia.
 * Permite tracing completo de workflows complexos.
 */
public class ExecutionId {

    private final String id;
    private final String parentId;
    private final ExecutionType type;
    private final int depth;
    private final Instant createdAt;

    public enum ExecutionType {
        FLOW,       // Execução de um flow completo
        AGENT,      // Execução de um agent
        TOOL,       // Execução de uma tool
        LLM,        // Chamada a um LLM
        PARALLEL    // Branch paralelo
    }

    private ExecutionId(Builder builder) {
        this.id = builder.id;
        this.parentId = builder.parentId;
        this.type = builder.type;
        this.depth = builder.depth;
        this.createdAt = builder.createdAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getId() { return id; }
    public String getParentId() { return parentId; }
    public ExecutionType getType() { return type; }
    public int getDepth() { return depth; }
    public Instant getCreatedAt() { return createdAt; }

    /**
     * Retorna o caminho completo da hierarquia.
     * Exemplo: "exec_root > exec_001 > exec_002"
     */
    public String getPath() {
        return parentId == null ? id : parentId + " > " + id;
    }

    /**
     * Cria um ExecutionId filho.
     */
    public ExecutionId child(ExecutionType childType) {
        return builder()
            .parentId(this.id)
            .type(childType)
            .depth(this.depth + 1)
            .build();
    }

    public static class Builder {
        private String id;
        private String parentId;
        private ExecutionType type;
        private Integer depth;
        private Instant createdAt;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder parentId(String parentId) {
            this.parentId = parentId;
            return this;
        }

        public Builder type(ExecutionType type) {
            this.type = type;
            return this;
        }

        public Builder depth(int depth) {
            this.depth = depth;
            return this;
        }

        public ExecutionId build() {
            if (id == null) {
                id = "exec_" + UUID.randomUUID().toString().substring(0, 8);
            }
            if (type == null) {
                type = ExecutionType.TOOL;
            }
            if (depth == null) {
                depth = parentId == null ? 0 : 1;
            }
            if (createdAt == null) {
                createdAt = Instant.now();
            }
            return new ExecutionId(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ExecutionId that = (ExecutionId) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ExecutionId{" +
            "id='" + id + '\'' +
            ", type=" + type +
            ", depth=" + depth +
            '}';
    }
}
```

**Arquivo**: `archflow-model/src/main/java/.../execution/ExecutionTracker.java`

```java
package br.com.archflow.model.execution;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Rastreia todas as execuções em memória (ou Redis para distribuído).
 */
@Component
public class ExecutionTracker {

    private final Map<String, ExecutionRecord> executions = new ConcurrentHashMap<>();

    public void start(ExecutionId executionId, Map<String, Object> metadata) {
        ExecutionRecord record = new ExecutionRecord(
            executionId,
            ExecutionStatus.STARTED,
            Instant.now(),
            metadata
        );
        executions.put(executionId.getId(), record);
    }

    public void complete(ExecutionId executionId, Object result) {
        ExecutionRecord record = executions.get(executionId.getId());
        if (record != null) {
            record.complete(result);
        }
    }

    public void fail(ExecutionId executionId, Throwable error) {
        ExecutionRecord record = executions.get(executionId.getId());
        if (record != null) {
            record.fail(error);
        }
    }

    /**
     * Retorna a hierarquia completa de execuções.
     */
    public List<ExecutionRecord> getHierarchy(String rootExecutionId) {
        List<ExecutionRecord> hierarchy = new ArrayList<>();
        collectChildren(rootExecutionId, hierarchy);
        return hierarchy;
    }

    private void collectChildren(String executionId, List<ExecutionRecord> result) {
        ExecutionRecord record = executions.get(executionId);
        if (record != null) {
            result.add(record);
            // Encontra filhos
            executions.values().stream()
                .filter(r -> executionId.equals(r.getParentId()))
                .forEach(child -> collectChildren(child.getId(), result));
        }
    }

    /**
     * Visualização em ASCII da árvore de execução.
     */
    public String visualizeTree(String rootExecutionId) {
        StringBuilder sb = new StringBuilder();
        buildTree(rootExecutionId, "", true, sb);
        return sb.toString();
    }

    private void buildTree(String executionId, String prefix, boolean isLast,
                           StringBuilder sb) {
        ExecutionRecord record = executions.get(executionId);
        if (record == null) return;

        sb.append(prefix)
          .append(isLast ? "└── " : "├── ")
          .append(record.getExecutionId().getType())
          .append(" [")
          .append(record.getExecutionId().getId())
          .append("]")
          .append("\n");

        List<ExecutionRecord> children = executions.values().stream()
            .filter(r -> executionId.equals(r.getParentId()))
            .sorted(Comparator.comparing(r -> r.getCreatedAt()))
            .toList();

        for (int i = 0; i < children.size(); i++) {
            ExecutionRecord child = children.get(i);
            boolean last = i == children.size() - 1;
            String childPrefix = prefix + (isLast ? "    " : "│   ");
            buildTree(child.getId(), childPrefix, last, sb);
        }
    }
}
```

### Critérios de Aceite

- [ ] ToolInterceptorChain funcionando
- [ ] 4 interceptors implementados (Logging, Caching, Metrics, Guardrails)
- [ ] ExecutionId com suporte a hierarquia
- [ ] ExecutionTracker.visualizeTree() funcionando
- [ ] Testes unitários e integração

---

## Sprint 3: Streaming Protocol (1.5 semanas)

### Objetivo

Implementar protocolo SSE estruturado para streaming de AI responses.

### Especificação: ArchflowEvent

**Arquivo**: `archflow-model/src/main/java/.../streaming/ArchflowEvent.java`

```java
package br.com.archflow.model.streaming;

import java.time.Instant;
import java.util.Map;

/**
 * Evento de streaming estruturado.
 * Baseado no AIFlowy Chat Protocol com melhorias.
 */
public class ArchflowEvent {

    private final Envelope envelope;
    private final Data data;

    public ArchflowEvent(Envelope envelope, Data data) {
        this.envelope = envelope;
        this.data = data;
    }

    public static ArchflowEvent chatMessage(String content) {
        return new ArchflowEvent(
            Envelope.chat(),
            Data.chat(content)
        );
    }

    public static ArchflowEvent chatDelta(String delta) {
        return new ArchflowEvent(
            Envelope.chatDelta(),
            Data.delta(delta)
        );
    }

    public static ArchflowEvent interactionForm(String formId, FormSchema schema) {
        return new ArchflowEvent(
            Envelope.interaction(),
            Data.form(formId, schema)
        );
    }

    public static ArchflowEvent thinking(String thought) {
        return new ArchflowEvent(
            Envelope.thinking(),
            Data.thinking(thought)
        );
    }

    public static ArchflowEvent toolStart(String toolName, Map<String, Object> input) {
        return new ArchflowEvent(
            Envelope.tool(),
            Data.toolStart(toolName, input)
        );
    }

    public static ArchflowEvent toolComplete(String toolName, Object output) {
        return new ArchflowEvent(
            Envelope.tool(),
            Data.toolComplete(toolName, output)
        );
    }

    public static ArchflowEvent audit(String executionId, String message) {
        return new ArchflowEvent(
            Envelope.audit(),
            Data.audit(executionId, message)
        );
    }

    public String toJson() {
        // Implementação usando Jackson ou Gson
        return JsonUtil.toJson(this);
    }

    // ========== Nested Classes ==========

    public static class Envelope {
        private final EventDomain domain;
        private final EventType type;
        private final String id;
        private final long timestamp;

        public enum EventDomain {
            CHAT,       // Mensagens do modelo
            INTERACTION, // Forms, inputs do usuário
            THINKING,    // Processo de raciocínio (o1, etc.)
            TOOL,        // Execução de tools
            AUDIT       // Tracing, debugging
        }

        public enum EventType {
            MESSAGE,     // Mensagem completa
            DELTA,       // Parte de mensagem (streaming)
            FORM,        // Solicitação de input
            ERROR,       // Erro
            START,       // Início de operação
            COMPLETE,    // Fim de operação
            LOG          // Log informativo
        }

        private Envelope(EventDomain domain, EventType type) {
            this.domain = domain;
            this.type = type;
            this.id = UUID.randomUUID().toString();
            this.timestamp = Instant.now().toEpochMilli();
        }

        public static Envelope chat() {
            return new Envelope(EventDomain.CHAT, EventType.MESSAGE);
        }

        public static Envelope chatDelta() {
            return new Envelope(EventDomain.CHAT, EventType.DELTA);
        }

        public static Envelope interaction() {
            return new Envelope(EventDomain.INTERACTION, EventType.FORM);
        }

        public static Envelope thinking() {
            return new Envelope(EventDomain.THINKING, EventType.LOG);
        }

        public static Envelope tool() {
            return new Envelope(EventDomain.TOOL, EventType.START);
        }

        public static Envelope audit() {
            return new Envelope(EventDomain.AUDIT, EventType.LOG);
        }

        // Getters
        public EventDomain getDomain() { return domain; }
        public EventType getType() { return type; }
        public String getId() { return id; }
        public long getTimestamp() { return timestamp; }
    }

    public static class Data {
        private final Map<String, Object> fields;

        public Data(Map<String, Object> fields) {
            this.fields = fields;
        }

        public static Data chat(String content) {
            return new Data(Map.of("content", content));
        }

        public static Data delta(String delta) {
            return new Data(Map.of("delta", delta));
        }

        public static Data form(String formId, FormSchema schema) {
            return new Data(Map.of(
                "formId", formId,
                "schema", schema.toMap()
            ));
        }

        public static Data thinking(String thought) {
            return new Data(Map.of("thought", thought));
        }

        public static Data toolStart(String toolName, Map<String, Object> input) {
            return new Data(Map.of(
                "toolName", toolName,
                "action", "start",
                "input", input
            ));
        }

        public static Data toolComplete(String toolName, Object output) {
            return new Data(Map.of(
                "toolName", toolName,
                "action", "complete",
                "output", output
            ));
        }

        public static Data audit(String executionId, String message) {
            return new Data(Map.of(
                "executionId", executionId,
                "message", message
            ));
        }

        // Getters
        public Object get(String key) {
            return fields.get(key);
        }

        public Map<String, Object> getFields() {
            return fields;
        }
    }
}
```

### Especificação: StreamingController

**Arquivo**: `archflow-server/src/main/java/.../streaming/StreamingController.java`

```java
package br.com.archflow.server.streaming;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/chat")
public class StreamingController {

    @Autowired
    private ChatService chatService;

    @Autowired
    private ExecutionTracker executionTracker;

    /**
     * Endpoint SSE para streaming de chat.
     *
     * Exemplo de eventos:
     * event: message
     * data: {"envelope":{"domain":"CHAT","type":"MESSAGE"...}, "data":{"content":"Olá!"}}
     *
     * event: delta
     * data: {"envelope":{"domain":"CHAT","type":"DELTA"...}, "data":{"delta":" mundo"}}
     *
     * event: tool
     * data: {"envelope":{"domain":"TOOL","type":"START"...}, "data":{"toolName":"search",...}}
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(
            @RequestParam String sessionId,
            @RequestParam(required = false) String flowId,
            @RequestBody String userMessage) {

        // Cria emitter com timeout longo
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L); // 30 minutos

        // Gera executionId root
        ExecutionId rootExecutionId = ExecutionId.builder()
            .type(ExecutionId.ExecutionType.FLOW)
            .build();

        executionTracker.start(rootExecutionId, Map.of(
            "sessionId", sessionId,
            "flowId", flowId
        ));

        // Envia evento inicial
        sendEvent(emitter, ArchflowEvent.audit(
            rootExecutionId.getId(),
            "Chat started"
        ));

        // Executa chat com streaming
        chatService.streamChat(sessionId, flowId, userMessage, rootExecutionId)
            .doOnNext(event -> {
                sendEvent(emitter, event);

                // Atualiza tracker
                if (event.getEnvelope().getDomain() == ArchflowEvent.Envelope.EventDomain.AUDIT) {
                    // Log events podem atualizar o tracker
                }
            })
            .doOnComplete(() -> {
                executionTracker.complete(rootExecutionId, null);
                sendEvent(emitter, ArchflowEvent.audit(
                    rootExecutionId.getId(),
                    "Chat completed"
                ));
                emitter.complete();
            })
            .doOnError(error -> {
                executionTracker.fail(rootExecutionId, error);
                emitter.completeWithError(error);
            })
            .subscribe();

        return emitter;
    }

    private void sendEvent(SseEmitter emitter, ArchflowEvent event) {
        try {
            emitter.send(SseEmitter.event()
                .name(event.getEnvelope().getDomain().name().toLowerCase())
                .data(event.toJson())
            );
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }
}
```

### Especificação: ChatService com Streaming

**Arquivo**: `archflow-server/src/main/java/.../streaming/ChatService.java`

```java
package br.com.archflow.server.streaming;

import reactor.core.publisher.Flux;

@Service
public class ChatService {

    @Autowired
    private FlowEngine flowEngine;

    @Autowired
    private ToolExecutor toolExecutor;

    public Flux<ArchflowEvent> streamChat(
            String sessionId,
            String flowId,
            String userMessage,
            ExecutionId executionId) {

        return Flux.create(emitter -> {
            try {
                // 1. Executa flow
                FlowExecutionContext context = FlowExecutionContext.builder()
                    .sessionId(sessionId)
                    .flowId(flowId)
                    .input(Map.of("message", userMessage))
                    .executionId(executionId)
                    .build();

                // 2. Para cada step no flow
                for (FlowStep step : flowEngine.getSteps(flowId)) {
                    ExecutionId stepExecutionId = executionId.child(
                        ExecutionId.ExecutionType.TOOL
                    );

                    // Envia audit event
                    emitter.next(ArchflowEvent.audit(
                        stepExecutionId.getId(),
                        "Starting step: " + step.getName()
                    ));

                    // 3. Executa step
                    if (step.getType() == StepType.LLM) {
                        // Streaming de LLM
                        streamLLM(step, context, stepExecutionId, emitter);
                    } else if (step.getType() == StepType.TOOL) {
                        // Executa tool
                        executeTool(step, context, stepExecutionId, emitter);
                    }
                }

                emitter.complete();

            } catch (Exception e) {
                emitter.error(e);
            }
        });
    }

    private void streamLLM(FlowStep step, FlowExecutionContext context,
                          ExecutionId executionId, FluxSink<ArchflowEvent> emitter) {

        // Envia tool start
        emitter.next(ArchflowEvent.toolStart(
            step.getName(),
            Map.of("model", step.getModelName())
        ));

        ChatLanguageModel model = getModel(step);

        // Streaming com LangChain4j
        Flux<String> stream = model.generate(
            convertToMessages(context.getInput()),
            (token) -> {
                // Para cada token
                emitter.next(ArchflowEvent.chatDelta(token));
            }
        );

        // Consome o stream
        stream.subscribe(
            token -> {}, // Já enviado no callback
            error -> emitter.error(error),
            () -> {
                emitter.next(ArchflowEvent.toolComplete(
                    step.getName(),
                    null
                ));
            }
        );
    }

    private void executeTool(FlowStep step, FlowExecutionContext context,
                            ExecutionId executionId, FluxSink<ArchflowEvent> emitter) {

        Tool tool = getTool(step);
        ToolInput input = convertToToolInput(step, context);

        // Envia tool start
        emitter.next(ArchflowEvent.toolStart(
            tool.getName(),
            input.toMap()
        ));

        // Executa com interceptors
        ToolResult result = toolExecutor.execute(tool, input, executionId.getId());

        // Envia tool complete
        emitter.next(ArchflowEvent.toolComplete(
            tool.getName(),
            result.getOutput()
        ));
    }
}
```

### Critérios de Aceite

- [ ] ArchflowEvent spec implementada
- [ ] 5 domains suportados (chat, interaction, thinking, tool, audit)
- [ ] SSE endpoint funcionando
- [ ] Integração com ExecutionTracker
- [ ] Testes de streaming

---

## Sprint 4: MCP Integration (2 semanas)

### Objetivo

Implementar MCP (Model Context Protocol) v1.0 para interoperabilidade com tools externas.

### Arquitetura MCP

```
┌─────────────────────────────────────────────────────────────┐
│                    archflow MCP Server                       │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │   Tool       │  │   Prompt     │  │   Resource   │      │
│  │  Registry    │  │  Manager     │  │   Manager    │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
         ↓ MCP Protocol (SSE/HTTP)
┌─────────────────────────────────────────────────────────────┐
│              MCP Servers Externos                            │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │   GitHub     │  │   Google     │  │   Custom     │      │
│  │   MCP Server │  │   MCP Server │  │   MCP Server │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
```

### Especificação: MCP Server

**Arquivo**: `archflow-server/src/main/java/.../mcp/ArchflowMCPServer.java`

```java
package br.com.archflow.server.mcp;

import dev.langchain4j.mcp.server.McpServer;
import dev.langchain4j.mcp.server.McpServerOptions;
import dev.langchain4j.mcp.server.transport.stdio.StdioMcpTransport;

/**
 * Servidor MCP que expõe tools do archflow para outros clientes.
 *
 * Inicialização via STDIO:
 * java -jar archflow-server.jar mcp-server --stdio
 */
@Component
public class ArchflowMCPServer {

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private FlowEngine flowEngine;

    @Autowired
    private KnowledgeBaseManager knowledgeBaseManager;

    public void startStdioServer() {
        McpServerOptions options = McpServerOptions.builder()
            .serverInfo("archflow", "1.0.0")
            .build();

        McpServer server = new McpServer(options);

        // Registra tools
        registerTools(server);

        // Registra prompts
        registerPrompts(server);

        // Registra resources
        registerResources(server);

        // Inicia transport STDIO
        StdioMcpTransport transport = new StdioMcpTransport();
        server.start(transport);
    }

    private void registerTools(McpServer server) {

        // Tools nativas do archflow
        for (Tool tool : toolRegistry.getAllTools()) {
            server.addTool(convertToMCPTool(tool));
        }

        // Workflows como tools
        for (Flow flow : flowEngine.getAllFlows()) {
            server.addTool(convertWorkflowToMCPTool(flow));
        }

        // Knowledge base search
        server.addTool(McpTool.builder()
            .name("knowledge_base_search")
            .description("Search in knowledge base using RAG")
            .inputSchema(knowledgeBaseSearchSchema())
            .handler(this::searchKnowledgeBase)
            .build()
        );
    }

    private void registerPrompts(McpServer server) {
        server.addPrompt(McpPrompt.builder()
            .name("customer-support")
            .description("Template para suporte ao cliente")
            .arguments(
                Map.of(
                    "tone", McpPromptArgument.builder()
                        .description("Tom da resposta")
                        .required(false)
                        .build(),
                    "language", McpPromptArgument.builder()
                        .description("Idioma")
                        .required(false)
                        .build()
                )
            )
            .build());
    }

    private void registerResources(McpServer server) {
        // Expose knowledge bases como recursos
        for (KnowledgeBase kb : knowledgeBaseManager.getAll()) {
            server.addResource(McpResource.builder()
                .uri("kb://" + kb.getId())
                .name(kb.getName())
                .description("Base de conhecimento: " + kb.getName())
                .mimeType("application/json")
                .build());
        }
    }

    private String searchKnowledgeBase(Map<String, Object> arguments) {
        String query = (String) arguments.get("query");
        String kbId = (String) arguments.get("knowledgeBaseId");
        int limit = (Integer) arguments.getOrDefault("limit", 5);

        // Executa RAG search
        List<Document> results = knowledgeBaseManager.search(kbId, query, limit);

        return formatResults(results);
    }
}
```

### Especificação: MCP Client

**Arquivo**: `archflow-server/src/main/java/.../mcp/ArchflowMCPClient.java`

```java
package br.com.archflow.server.mcp;

import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.transport.McpTransport;
import dev.langchain4j.mcp.transport.http.McpHttpTransport;

/**
 * Cliente MCP para consumir tools de servidores externos.
 */
@Component
public class ArchflowMCPClient {

    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();

    public void connect(String serverName, String serverUrl) {
        McpTransport transport = McpHttpTransport.builder()
            .url(serverUrl)
            .build();

        McpClient client = McpClient.builder()
            .transport(transport)
            .build();

        client.initialize();
        clients.put(serverName, client);
    }

    public List<McpTool> listTools(String serverName) {
        McpClient client = clients.get(serverName);
        if (client == null) {
            throw new IllegalArgumentException("MCP server not connected: " + serverName);
        }
        return client.listTools();
    }

    public McpToolResult callTool(String serverName, String toolName, Map<String, Object> arguments) {
        McpClient client = clients.get(serverName);
        return client.callTool(toolName, arguments);
    }

    /**
     * Registra tools MCP como tools do archflow.
     */
    public void registerMcpToolsAsArchflowTools() {
        for (Map.Entry<String, McpClient> entry : clients.entrySet()) {
            String serverName = entry.getKey();
            McpClient client = entry.getValue();

            for (McpTool mcpTool : client.listTools()) {
                Tool archflowTool = new MCPToolWrapper(serverName, mcpTool);
                toolRegistry.register(archflowTool);
            }
        }
    }

    /**
     * Wrapper que adapta MCP tool para Tool interface.
     */
    private static class MCPToolWrapper implements Tool {

        private final String serverName;
        private final McpTool mcpTool;
        private final McpClient client;

        MCPToolWrapper(String serverName, McpTool mcpTool) {
            this.serverName = serverName;
            this.mcpTool = mcpTool;
            this.client = archflowMCPClient.getClient(serverName);
        }

        @Override
        public String getName() {
            return serverName + "." + mcpTool.getName();
        }

        @Override
        public String getDescription() {
            return "[MCP:" + serverName + "] " + mcpTool.getDescription();
        }

        @Override
        public ToolResult execute(ToolInput input) {
            Map<String, Object> arguments = input.toMap();

            // Valida schema
            validateArguments(mcpTool.getInputSchema(), arguments);

            // Chama MCP
            McpToolResult result = client.callTool(mcpTool.getName(), arguments);

            return ToolResult.from(result);
        }
    }
}
```

### Especificação: Configuration para MCP Servers

**Arquivo**: `archflow-server/src/main/resources/application-mcp.yml`

```yaml
archflow:
  mcp:
    # MCP Servers para conectar automaticamente
    servers:
      github:
        url: http://localhost:3000/mcp/github
        enabled: true
      google-drive:
        url: http://localhost:3001/mcp/gdrive
        enabled: true
      custom:
        url: ${CUSTOM_MCP_SERVER_URL}
        enabled: false
```

### Critérios de Aceite

- [ ] MCP Server rodando (STDIO)
- [ ] MCP Client para servidores externos
- [ ] Tools MCP registradas como tools archflow
- [ ] Workflows expostos como MCP tools
- [ ] Knowledge bases como MCP resources
- [ ] Configuração via YAML

---

## Critérios de Sucesso da Fase 1

- [ ] LangChain4j 1.10.0 integrado sem breaking errors
- [ ] Tool execution com 4+ interceptors funcionando
- [ ] ExecutionTracker.visualizeTree() mostrando hierarquia
- [ ] Streaming SSE com 5 domains implementados
- [ ] MCP Server expõe tools archflow
- [ ] MCP Client consome tools externas
- [ ] Cobertura de testes > 80%
- [ ] Documentação de APIs atualizada

---

## Próximos Passos Após Fase 1

1. **Merge para branch main**
2. **Tag versão 0.1.0-alpha1**
3. **Deploy em staging**
4. **Iniciar Fase 2: Visual Experience**

---

## Appendix: Comandos Úteis

### Build e Test

```bash
# Build completo
mvn clean install

# Testes de um módulo
mvn test -pl archflow-langchain4j-core

# Testes com coverage
mvn clean verify
```

### Execução Local

```bash
# Start MCP Server (STDIO)
java -jar archflow-server/target/archflow-server.jar mcp-server --stdio

# Start com streaming
java -jar archflow-server/target/archflow-server.jar

# Testar MCP Server
echo '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | java -jar archflow-server.jar
```

### Debug

```bash
# Habilita debug logging
export LOGGING_LEVEL_ROOT=DEBUG
java -jar archflow-server.jar
```
