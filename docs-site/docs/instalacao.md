---
title: Instalação
sidebar_position: 2
slug: instalacao
---

# Instalação

## Requisitos

- **Java 17+** (Java 21+ recomendado para virtual threads)
- **Maven 3.9+**
- **Node.js 18+** (para documentação e UI)

## Dependências Maven

Adicione o archflow ao seu projeto:

```xml
<dependency>
    <groupId>br.com.archflow</groupId>
    <artifactId>archflow-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Módulos Individuais

Você também pode usar módulos específicos:

```xml
<!-- Core Engine -->
<dependency>
    <groupId>br.com.archflow</groupId>
    <artifactId>archflow-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- LangChain4j Integration -->
<dependency>
    <groupId>br.com.archflow</groupId>
    <artifactId>archflow-langchain4j-openai</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- Workflow Templates -->
<dependency>
    <groupId>br.com.archflow</groupId>
    <artifactId>archflow-templates</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- Performance -->
<dependency>
    <groupId>br.com.archflow</groupId>
    <artifactId>archflow-performance</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Spring Boot Configuration

```yaml
archflow:
  api:
    base-path: /api
  security:
    enabled: true
    api-key: ${ARCHFLOW_API_KEY}
  llm:
    provider: openai
    api-key: ${OPENAI_API_KEY}
    model: gpt-4o
```

## Docker

```bash
docker run -d \
  -p 8080:8080 \
  -e ARCHFLOW_API_KEY=seu-chave-aqui \
  -e OPENAI_API_KEY=$OPENAI_API_KEY \
  ghcr.io/archflow/server:1.0.0
```

## Web Component

Instale o componente:

```bash
npm install @archflow/component
```

Use em qualquer framework:

```html
<archflow-designer
  workflow-id="customer-support"
  api-base="http://localhost:8080/api"
  theme="dark">
</archflow-designer>
```
