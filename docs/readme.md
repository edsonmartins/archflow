# archflow

<div align="center">

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Build Status](https://img.shields.io/github/actions/workflow/status/archflow/archflow/build.yml?branch=main)](https://github.com/archflow/archflow/actions)
[![Maven Central](https://img.shields.io/maven-central/v/br.com.archflow/archflow.svg)](https://search.maven.org/search?q=g:br.com.archflow)
[![Java Version](https://img.shields.io/badge/java-%3E%3D17-orange)](https://adoptium.net/)

</div>

O archflow é um framework open source para automação de agentes de IA em Java, construído sobre o [LangChain4j](https://github.com/langchain4j/langchain4j). 

## 🌟 Destaques

- 🚀 **Baseado em LangChain4j**: Aproveite todo o poder do ecossistema LangChain no mundo Java
- 🔄 **Orquestração Robusta**: Construído sobre Apache Camel para execução confiável de fluxos
- 🔌 **Sistema de Plugins**: Extensível através de um sistema modular de plugins
- 📊 **Monitoramento**: Métricas e observabilidade em tempo real
- 🛡️ **Pronto para Produção**: Desenvolvido pensando em ambientes empresariais

## 🚀 Início Rápido

### Maven

```xml
<dependency>
    <groupId>br.com.archflow</groupId>
    <artifactId>archflow-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Exemplo Básico

```java
@AIComponentDescriptor(
    type = "assistant",
    name = "customer-support"
)
public class CustomerSupportFlow implements AIFlow {
    // Implementação do fluxo
}
```

## 📚 Documentação

- [Visão Geral do Projeto](docs/README.md)
- [Guia de Arquitetura](docs/architecture/README.md)
- [Guia de Desenvolvimento](docs/development/README.md)
- [Exemplos](docs/examples/README.md)
- [JavaDoc](https://archflow.github.io/docs/javadoc)

## 🌱 Módulos

- **archflow-core**: Contratos e interfaces principais
- **archflow-model**: Modelos de domínio
- **archflow-plugin-api**: API para desenvolvimento de plugins
- **archflow-engine**: Motor de execução baseado em Camel
- **archflow-api**: REST API para gerenciamento
- **archflow-agent**: Agente de execução distribuída
- **archflow-langchain4j**: Integração com LangChain4j
- **archflow-plugin-loader**: Carregamento dinâmico e gestão de plugins em runtime
- **archflow-plugin-processor**: Processamento de anotações e geração de metadados de plugins

## 🤝 Contribuindo

Contribuições são bem-vindas! Por favor, leia nosso [Guia de Contribuição](development/contributing.md) antes de começar.

### Setup do Ambiente

```bash
# Clone o repositório
git clone https://github.com/archflow/archflow.git

# Entre no diretório
cd archflow

# Instale as dependências
mvn clean install
```

## 🔗 Links Úteis

- [Website](https://archflow.github.io)
- [Exemplos](https://github.com/archflow/archflow-examples)
- [Discord](https://discord.gg/archflow)
- [Issue Tracker](https://github.com/archflow/archflow/issues)

## 📄 Licença

Este projeto está licenciado sob a [Apache License 2.0](LICENSE).

## 🙏 Agradecimentos

- [LangChain4j](https://github.com/langchain4j/langchain4j) - Por fornecer a base do framework
- [Apache Camel](https://camel.apache.org/) - Pela robusta engine de integração
- Todos os [contribuidores](https://github.com/archflow/archflow/graphs/contributors) que ajudam a melhorar o projeto

---

<div align="center">
⭐️ Se você gosta do archflow, por favor considere dar uma estrela no projeto! ⭐️
</div>
