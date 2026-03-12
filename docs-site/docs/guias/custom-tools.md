---
title: "Custom Tools"
sidebar_position: 3
slug: custom-tools
---

# Custom Tools

Guia para criar ferramentas (tools) customizadas no archflow.

## O que sao Tools no archflow

**Tools** sao componentes que permitem aos agentes AI interagir com o mundo externo -- chamar APIs, buscar dados, executar calculos, manipular arquivos, etc. No archflow, tools implementam uma interface padronizada que garante:

- **Validacao** de parametros de entrada
- **Descricao** legivel por LLMs (para function calling)
- **Interceptores** (guardrails, caching, logging)
- **Integracao** com workflows como steps do tipo `TOOL_CALL`

```
┌──────────────────────────────────────────────────┐
│                    Agente AI                      │
│                       │                          │
│              "Preciso calcular X"                │
│                       │                          │
│                       ▼                          │
│  ┌──────────────────────────────────────┐       │
│  │           Tool Executor               │       │
│  │  ┌──────────┐  ┌──────────────────┐  │       │
│  │  │Guardrails│→ │  Calculator Tool  │  │       │
│  │  │Interceptor│  │  - validate()    │  │       │
│  │  └──────────┘  │  - execute()     │  │       │
│  │  ┌──────────┐  │  - getParams()   │  │       │
│  │  │  Caching  │← └──────────────────┘  │       │
│  │  │Interceptor│                        │       │
│  │  └──────────┘                         │       │
│  └──────────────────────────────────────┘       │
└──────────────────────────────────────────────────┘
```

## Interface Tool

A interface `Tool` define o contrato que toda ferramenta deve implementar:

```java
public interface Tool {

    /**
     * Executa a ferramenta com os parametros fornecidos.
     */
    Result execute(Map<String, Object> params, ExecutionContext context);

    /**
     * Retorna a lista de parametros aceitos pela ferramenta.
     * Usado pelo LLM para function calling.
     */
    List<ParameterDescription> getParameters();

    /**
     * Valida os parametros antes da execucao.
     * Lanca IllegalArgumentException se invalido.
     */
    void validateParameters(Map<String, Object> params);
}
```

Alem disso, toda tool tambem implementa `AIComponent` (interface base) e `ComponentPlugin` (SPI):

```java
public class MyTool implements Tool, ComponentPlugin {
    // Tool: execute(), getParameters(), validateParameters()
    // AIComponent: initialize(), getMetadata(), execute(op, input, ctx), shutdown()
    // ComponentPlugin: validateConfig(), onLoad(), onUnload()
}
```

## Criando uma Tool Simples

Exemplo: uma ferramenta de calculadora.

```java
package com.example.plugins;

import br.com.archflow.model.ai.Tool;
import br.com.archflow.model.ai.domain.*;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.plugin.api.spi.ComponentPlugin;

import java.util.*;

public class CalculatorTool implements Tool, ComponentPlugin {

    private boolean initialized = false;

    // ── ComponentPlugin (lifecycle) ──

    @Override
    public void validateConfig(Map<String, Object> config) {
        // Sem configuracao obrigatoria
    }

    @Override
    public void onLoad(ExecutionContext context) {
        // Plugin carregado no catalogo
    }

    @Override
    public void onUnload() {
        // Cleanup
    }

    // ── AIComponent (base) ──

    @Override
    public void initialize(Map<String, Object> config) {
        this.initialized = true;
    }

    @Override
    public ComponentMetadata getMetadata() {
        return new ComponentMetadata(
            "calculator-tool",
            "Calculator",
            "Performs basic arithmetic operations",
            ComponentType.TOOL,
            "1.0.0",
            Set.of("math", "calculation"),
            List.of(
                new ComponentMetadata.OperationMetadata(
                    "calculate", "Calculate", "Evaluate arithmetic expression",
                    List.of(new ComponentMetadata.ParameterMetadata(
                        "expression", "string", "Math expression (e.g., 2+3*4)", true)),
                    List.of(new ComponentMetadata.ParameterMetadata(
                        "result", "number", "Calculation result", true))
                )
            ),
            Map.of(),
            Set.of("math", "utility")
        );
    }

    @Override
    public Object execute(String operation, Object input, ExecutionContext context)
            throws Exception {
        if (!initialized) throw new IllegalStateException("Not initialized");
        if ("calculate".equals(operation)) {
            return evaluate((String) input);
        }
        throw new IllegalArgumentException("Unsupported operation: " + operation);
    }

    @Override
    public void shutdown() {
        this.initialized = false;
    }

    // ── Tool (interface especifica) ──

    @Override
    public Result execute(Map<String, Object> params, ExecutionContext context) {
        try {
            validateParameters(params);
            String expression = (String) params.get("expression");
            double result = evaluate(expression);
            return Result.success(result);
        } catch (Exception e) {
            return Result.failure(e.getMessage());
        }
    }

    @Override
    public List<ParameterDescription> getParameters() {
        return List.of(
            new ParameterDescription(
                "expression", "string",
                "Arithmetic expression to evaluate (e.g., 2+3*4)",
                true, null, List.of()
            )
        );
    }

    @Override
    public void validateParameters(Map<String, Object> params) {
        if (params == null || !params.containsKey("expression")) {
            throw new IllegalArgumentException("'expression' is required");
        }
        String expr = (String) params.get("expression");
        if (expr == null || expr.isBlank()) {
            throw new IllegalArgumentException("'expression' cannot be blank");
        }
        // Valida que contem apenas caracteres permitidos
        if (!expr.matches("[0-9+\\-*/().\\s]+")) {
            throw new IllegalArgumentException(
                "'expression' contains invalid characters. Only numbers and +-*/() allowed");
        }
    }

    // ── Logica interna ──

    private double evaluate(String expression) {
        // Implementacao simplificada usando javax.script
        try {
            var engine = new javax.script.ScriptEngineManager()
                .getEngineByName("JavaScript");
            Object result = engine.eval(expression);
            return ((Number) result).doubleValue();
        } catch (Exception e) {
            throw new RuntimeException("Failed to evaluate: " + expression, e);
        }
    }
}
```

## Registrando via SPI

Para que o archflow descubra sua tool automaticamente, crie o arquivo SPI:

```
src/main/resources/META-INF/services/br.com.archflow.plugin.api.spi.ComponentPlugin
```

Com o conteudo:

```
com.example.plugins.CalculatorTool
```

Ao iniciar o archflow, o `PluginLoader` descobre e registra a tool no catalogo automaticamente.

:::tip
Use `provided` scope para as dependencias do archflow no seu `pom.xml` -- elas ja estao disponiveis no runtime.
:::

## Tool dentro de um Workflow

Para usar sua tool em um workflow, adicione um step do tipo `TOOL_CALL`:

```json
{
  "steps": [
    {
      "id": "step-calculate",
      "type": "TOOL_CALL",
      "componentId": "calculator-tool",
      "operation": "calculate",
      "configuration": {
        "params": {
          "expression": "${previousStep.output.formula}"
        }
      },
      "connections": [
        {
          "sourceId": "step-calculate",
          "targetId": "step-format-result",
          "isErrorPath": false
        }
      ]
    }
  ]
}
```

Variaveis de contexto como `${previousStep.output.formula}` sao resolvidas em runtime pelo Flow Engine.

## Tool com Validacao de Input/Output

Para tools que precisam de validacao mais robusta, implemente verificacoes no `execute()`:

```java
@Override
public Result execute(Map<String, Object> params, ExecutionContext context) {
    try {
        // 1. Validar input
        validateParameters(params);

        // 2. Executar
        String query = (String) params.get("query");
        int maxResults = (int) params.getOrDefault("maxResults", 10);
        List<SearchResult> results = searchEngine.search(query, maxResults);

        // 3. Validar output
        if (results == null) {
            return Result.failure("Search returned null");
        }
        if (results.size() > maxResults) {
            results = results.subList(0, maxResults);
        }

        return Result.success(results);
    } catch (Exception e) {
        return Result.failure(e.getMessage());
    }
}
```

## Guardrails via GuardrailsInterceptor

O archflow oferece interceptores para adicionar guardrails (barreiras de seguranca) as suas tools:

```java
// Registrar guardrails no catalogo
pluginCatalog.registerInterceptor(
    "calculator-tool",
    new GuardrailsInterceptor(GuardrailsConfig.builder()
        .maxInputLength(1000)
        .blockedPatterns(List.of(
            "System\\.exit",
            "Runtime\\.exec",
            "ProcessBuilder"
        ))
        .maxExecutionTime(Duration.ofSeconds(5))
        .build()
    )
);
```

O `GuardrailsInterceptor` verifica automaticamente:

| Guardrail | Descricao |
|-----------|-----------|
| `maxInputLength` | Limita o tamanho do input |
| `blockedPatterns` | Bloqueia patterns perigosos (regex) |
| `maxExecutionTime` | Timeout para execucao da tool |
| `allowedDomains` | Restringe dominios para tools de rede |
| `rateLimit` | Limita chamadas por periodo |

:::caution
Guardrails sao essenciais para tools que executam codigo ou acessam recursos externos. Sempre configure limites adequados.
:::

## Caching com CachingInterceptor

Para tools com resultados deterministicos, use o `CachingInterceptor` para evitar chamadas repetidas:

```java
pluginCatalog.registerInterceptor(
    "calculator-tool",
    new CachingInterceptor(CachingConfig.builder()
        .ttl(Duration.ofMinutes(30))
        .maxEntries(1000)
        .keyGenerator(params -> params.get("expression").toString())
        .build()
    )
);
```

| Parametro | Default | Descricao |
|-----------|---------|-----------|
| `ttl` | 5 min | Tempo de vida do cache |
| `maxEntries` | 500 | Numero maximo de entradas |
| `keyGenerator` | hash dos params | Funcao para gerar chave de cache |
| `cacheProvider` | in-memory | `IN_MEMORY` ou `REDIS` |

Para usar Redis como backend de cache:

```java
CachingConfig.builder()
    .cacheProvider(CacheProvider.REDIS)
    .redisUrl("redis://localhost:6379")
    .keyPrefix("archflow:tool-cache:")
    .build()
```

## Referencia: Tools Built-in

O archflow inclui tools de referencia no modulo `archflow-plugin-tools`:

| Tool | Operacoes | Descricao |
|------|-----------|-----------|
| `TextTransformTool` | uppercase, lowercase, reverse, wordcount | Transformacoes de texto |
| `WordCountTool` | count | Contagem de palavras |

Para criar suas proprias tools, siga o padrao dos plugins de referencia em `archflow-plugins/archflow-plugin-tools/`.

## Testes

Use JUnit 5 + AssertJ para testar suas tools:

```java
@DisplayName("CalculatorTool")
class CalculatorToolTest {

    private CalculatorTool tool;
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        tool = new CalculatorTool();
        context = mock(ExecutionContext.class);
        tool.initialize(Map.of());
    }

    @Test
    @DisplayName("should calculate simple expression")
    void shouldCalculate() {
        var result = tool.execute(Map.of("expression", "2+3*4"), context);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getValue()).isEqualTo(14.0);
    }

    @Test
    @DisplayName("should reject invalid characters")
    void shouldRejectInvalid() {
        assertThatThrownBy(() ->
            tool.validateParameters(Map.of("expression", "System.exit(0)"))
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should require expression parameter")
    void shouldRequireExpression() {
        var result = tool.execute(Map.of(), context);
        assertThat(result.isSuccess()).isFalse();
    }
}
```

## Proximos passos

- [Building Workflows](./building-workflows) -- Usar tools dentro de workflows
- [Plugin Development](./plugin-development) -- Guia completo de desenvolvimento de plugins
- [Conceitos: Tools](../conceitos/tools) -- Teoria e arquitetura de tools
