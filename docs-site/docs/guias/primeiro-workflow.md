---
title: Primeiro Workflow
sidebar_position: 1
slug: primeiro-workflow
---

# Criando seu Primeiro Workflow

Este guia mostra como criar e executar seu primeiro workflow AI com archflow.

## Pré-requisitos

- Java 17+ instalado
- Maven 3.9+
- Chave de API da OpenAI (ou outro provider)

## 1. Configuração do Projeto

### Adicione a dependência:

```xml
<dependency>
    <groupId>br.com.archflow</groupId>
    <artifactId>archflow-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Configure o application.yml:

```yaml
archflow:
  llm:
    provider: openai
    api-key: ${OPENAI_API_KEY}
    model: gpt-4o
```

## 2. Criando um Workflow Simples

Vamos criar um workflow que resume textos:

```java
import br.com.archflow.core.*;
import br.com.archflow.model.*;
import br.com.archflow.agent.*;

@Service
public class SummarizeWorkflow {

    private final FlowEngine flowEngine;
    private final ChatLanguageModel llm;

    @PostConstruct
    public void createWorkflow() {
        Workflow workflow = Workflow.builder()
            .id("summarize")
            .name("Resumidor de Textos")
            .description("Resume textos longos")
            .nodes(List.of(
                // Nó de entrada
                InputNode.builder()
                    .id("input")
                    .field("text", "Texto para resumir")
                    .build(),

                // Nó LLM
                LLMNode.builder()
                    .id("summarizer")
                    .model("gpt-4o")
                    .prompt("""
                        Resuma o seguinte texto de forma concisa:
                        {input.text}
                        """)
                    .maxTokens(200)
                    .build(),

                // Nó de saída
                OutputNode.builder()
                    .id("output")
                    .template("${summarizer.output}")
                    .build()
            ))
            .edges(List.of(
                Edge.from("input").to("summarizer"),
                Edge.from("summarizer").to("output")
            ))
            .build();

        flowEngine.register(workflow);
    }

    public String summarize(String text) {
        return flowEngine.execute("summarize", Map.of(
            "text", text
        ));
    }
}
```

## 3. Criando um Controller REST

```java
@RestController
@RequestMapping("/api")
public class SummarizeController {

    private final SummarizeWorkflow workflow;

    @PostMapping("/summarize")
    public ResponseEntity<Map<String, String>> summarize(
            @RequestBody Map<String, String> request) {

        String summary = workflow.summarize(request.get("text"));

        return ResponseEntity.ok(Map.of(
            "summary", summary
        ));
    }
}
```

## 4. Testando

```bash
curl -X POST http://localhost:8080/api/summarize \
  -H "Content-Type: application/json" \
  -d '{
    "text": "O archflow é uma plataforma visual para criar workflows de IA..."
  }'
```

## 5. Visualizando no Designer

Use o Web Component para visualizar e editar:

```html
<!DOCTYPE html>
<html>
<head>
    <script type="module" src="@archflow/component"></script>
</head>
<body>
    <archflow-designer
        workflow-id="summarize"
        api-base="http://localhost:8080/api"
        theme="dark">
    </archflow-designer>
</body>
</html>
```

## Próximos Passos

- [Agente AI](/docs/guias/agente-ia) - Criando agentes AI
- [RAG](/docs/guias/rag) - Implementando RAG
- [Multi-Agente](/docs/guias/multi-agente) - Coordenação de múltiplos agentes
