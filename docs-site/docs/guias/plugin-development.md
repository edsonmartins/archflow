---
title: Plugin Development Guide
sidebar_position: 5
slug: plugin-development
---

# Guia de Desenvolvimento de Plugins

Como criar plugins customizados para o archflow.

## Visão Geral

Plugins no archflow são componentes que implementam duas interfaces:
1. **`ComponentPlugin`** (SPI) — Ciclo de vida do plugin
2. **Uma interface AI** — `Tool`, `AIAssistant`, ou `AIAgent`

```
ComponentPlugin (SPI)          AIComponent (base)
├─ validateConfig()            ├─ initialize()
├─ onLoad()                    ├─ getMetadata()
└─ onUnload()                  ├─ execute()
                               └─ shutdown()
                                   │
                    ┌──────────────┼──────────────┐
                    │              │              │
                   Tool      AIAssistant      AIAgent
```

## 1. Configuração do Projeto

Crie um módulo Maven com as dependências do archflow:

```xml
<project>
    <parent>
        <groupId>br.com.archflow.plugins</groupId>
        <artifactId>archflow-plugins</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>my-custom-plugin</artifactId>

    <dependencies>
        <dependency>
            <groupId>br.com.archflow</groupId>
            <artifactId>archflow-plugin-api</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>br.com.archflow</groupId>
            <artifactId>archflow-model</artifactId>
            <scope>provided</scope>
        </dependency>
    </dependencies>
</project>
```

:::tip
Use `provided` scope — estas dependências já estão disponíveis no runtime do archflow.
:::

## 2. Escolha o tipo de componente

### Tool — Ferramenta específica

Para operações pontuais com parâmetros definidos (ex: web search, file parser, calculator).

```java
public class MyTool implements Tool, ComponentPlugin { ... }
```

### AIAssistant — Assistente interativo

Para componentes que analisam requests e geram respostas (ex: tech support, writing assistant).

```java
public class MyAssistant implements AIAssistant, ComponentPlugin { ... }
```

### AIAgent — Agente autônomo

Para componentes que executam tarefas, tomam decisões e planejam ações (ex: research agent).

```java
public class MyAgent implements AIAgent, ComponentPlugin { ... }
```

## 3. Exemplo completo: Tool

```java
package com.example.plugins;

import br.com.archflow.model.ai.Tool;
import br.com.archflow.model.ai.domain.*;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.plugin.api.spi.ComponentPlugin;

import java.util.*;

public class WordCountTool implements Tool, ComponentPlugin {

    private boolean initialized = false;

    // ── ComponentPlugin (SPI lifecycle) ──

    @Override
    public void validateConfig(Map<String, Object> config) {
        // Valide configurações necessárias
    }

    @Override
    public void onLoad(ExecutionContext context) {
        // Chamado quando o plugin é registrado no catálogo
    }

    @Override
    public void onUnload() {
        // Cleanup ao descarregar o plugin
    }

    // ── AIComponent (base) ──

    @Override
    public void initialize(Map<String, Object> config) {
        validateConfig(config);
        this.initialized = true;
    }

    @Override
    public ComponentMetadata getMetadata() {
        return new ComponentMetadata(
            "word-count-tool",              // id único
            "Word Count Tool",              // nome de exibição
            "Counts words in text input",   // descrição
            ComponentType.TOOL,             // tipo
            "1.0.0",                        // versão semântica
            Set.of("text-processing"),      // capabilities
            List.of(                        // operações
                new ComponentMetadata.OperationMetadata(
                    "count", "Count Words", "Count words in text",
                    List.of(new ComponentMetadata.ParameterMetadata(
                        "text", "string", "Input text", true)),
                    List.of(new ComponentMetadata.ParameterMetadata(
                        "result", "integer", "Word count", true))
                )
            ),
            Map.of(),                       // propriedades adicionais
            Set.of("text", "utility")       // tags
        );
    }

    @Override
    public Object execute(String operation, Object input, ExecutionContext context)
            throws Exception {
        if (!initialized) {
            throw new IllegalStateException("Not initialized");
        }
        if ("count".equals(operation)) {
            String text = (String) input;
            return text.isBlank() ? 0 : text.trim().split("\\s+").length;
        }
        throw new IllegalArgumentException("Unsupported operation: " + operation);
    }

    @Override
    public void shutdown() {
        this.initialized = false;
    }

    // ── Tool (interface específica) ──

    @Override
    public Result execute(Map<String, Object> params, ExecutionContext context) {
        try {
            String text = (String) params.get("text");
            Object result = execute("count", text, context);
            return Result.success(result);
        } catch (Exception e) {
            return Result.failure(e.getMessage());
        }
    }

    @Override
    public List<ParameterDescription> getParameters() {
        return List.of(
            new ParameterDescription(
                "text", "string", "Text to count words",
                true, null, List.of()
            )
        );
    }

    @Override
    public void validateParameters(Map<String, Object> params) {
        if (params == null || !params.containsKey("text")) {
            throw new IllegalArgumentException("'text' is required");
        }
    }
}
```

## 4. Metadata

O `ComponentMetadata` é fundamental — ele descreve o plugin para o catálogo e para a UI:

| Campo | Obrigatório | Descrição |
|-------|-------------|-----------|
| `id` | Sim | Identificador único (ex: `my-word-count-tool`) |
| `name` | Não | Nome para exibição na UI |
| `description` | Não | Descrição do que o plugin faz |
| `type` | Sim | `TOOL`, `ASSISTANT`, `AGENT`, ou `PLUGIN` |
| `version` | Sim | Versão semântica (ex: `1.0.0`) |
| `capabilities` | Não | Set de capacidades para busca no catálogo |
| `operations` | Não | Lista de operações com inputs/outputs |
| `properties` | Não | Propriedades adicionais (key-value) |
| `tags` | Não | Tags para categorização |

## 5. Registro via SPI

Para que o archflow descubra seu plugin automaticamente, crie o arquivo SPI:

```
src/main/resources/META-INF/services/br.com.archflow.plugin.api.spi.ComponentPlugin
```

Com o conteúdo:

```
com.example.plugins.WordCountTool
```

## 6. Anotações (opcional)

Você pode usar anotações declarativas ao invés do `getMetadata()`:

```java
@ComponentDescriptor(
    id = "word-count-tool",
    name = "Word Count Tool",
    type = ComponentType.TOOL,
    version = "1.0.0",
    operations = {
        @Operation(
            id = "count",
            name = "Count Words",
            inputs = @Property(id = "text", name = "Text", type = "string", required = true),
            outputs = @Property(id = "result", name = "Result", type = "integer")
        )
    }
)
public class WordCountTool implements Tool, ComponentPlugin { ... }
```

## 7. Testes

Use o padrão do projeto (JUnit 5 + AssertJ + Mockito):

```java
@DisplayName("WordCountTool")
class WordCountToolTest {

    private WordCountTool tool;
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        tool = new WordCountTool();
        context = mock(ExecutionContext.class);
        tool.initialize(Map.of());
    }

    @Test
    @DisplayName("should count words correctly")
    void shouldCountWords() throws Exception {
        var result = tool.execute("count", "hello world foo", context);
        assertThat(result).isEqualTo(3);
    }

    @Test
    @DisplayName("should return zero for blank text")
    void shouldReturnZeroForBlank() throws Exception {
        var result = tool.execute("count", "   ", context);
        assertThat(result).isEqualTo(0);
    }

    @Test
    @DisplayName("should fail when not initialized")
    void shouldFailNotInitialized() {
        var uninit = new WordCountTool();
        assertThatThrownBy(() -> uninit.execute("count", "text", context))
            .isInstanceOf(IllegalStateException.class);
    }
}
```

## 8. Classloader Isolation

Plugins rodam em classloaders isolados (`ArchflowPluginClassLoader`). Pacotes compartilhados com o host:

- `br.com.archflow.model.*` — Interfaces base
- `br.com.archflow.plugin.api.*` — SPI
- `dev.langchain4j.*` — LangChain4j
- `org.apache.camel.*` — Apache Camel

Tudo mais é isolado no classloader do plugin, prevenindo conflitos de dependências.

## 9. Plugins de referência

O projeto inclui 3 plugins de referência como exemplos:

| Plugin | Tipo | Descrição |
|--------|------|-----------|
| `TextTransformTool` | Tool | Transformações de texto (uppercase, lowercase, reverse, wordcount) |
| `TechSupportAssistant` | AIAssistant | Assistente de suporte técnico baseado em patterns |
| `ResearchAgent` | AIAgent | Agente de pesquisa com decomposição de tarefas |

Código fonte em `archflow-plugins/archflow-plugin-{tools,assistants,agents}/`.

## Próximos passos

- [API Reference](../api/rest-endpoints) — Endpoints REST para gerenciar plugins
- [Conceitos](../conceitos/) — Arquitetura do archflow
- [LangChain4j Integration](../api/api-langchain4j) — Integração com modelos de IA
