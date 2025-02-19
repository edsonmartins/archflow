# archflow

<div align="center">

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Build Status](https://img.shields.io/github/actions/workflow/status/archflow/archflow/build.yml?branch=main)](https://github.com/archflow/archflow/actions)
[![Maven Central](https://img.shields.io/maven-central/v/br.com.archflow/archflow.svg)](https://search.maven.org/search?q=g:br.com.archflow)
[![Java Version](https://img.shields.io/badge/java-%3E%3D17-orange)](https://adoptium.net/)
[![Discord](https://img.shields.io/discord/1234567890?color=7289da&label=discord)](https://discord.gg/archflow)

<img src="docs/images/logo_horizontal.svg" alt="archflow Logo" width="300"/>

**Framework open source para automação de agentes de IA em Java**

[Website](https://archflow.github.io) • [Documentação](docs/README.md) • [Quickstart](docs/development/quickstart.md) • [Discord](https://discord.gg/archflow)

</div>

## ✨ Por que archflow?

O archflow é uma plataforma LowCode que permite criar, executar e gerenciar fluxos de trabalho baseados em IA de forma robusta e escalável. Construído sobre [LangChain4j](https://github.com/langchain4j/langchain4j) e Apache Camel, com suporte a múltiplas linguagens via GraalVM, ele traz:

- 🎯 **LowCode**: Desenvolva usando nossa interface visual drag-and-drop ou código em Java, JavaScript e Python
- 🚀 **Performance**: Execução otimizada e escalável usando GraalVM para ambientes de produção
- 🔌 **Extensibilidade**: Sistema de plugins multilinguagem para adicionar novas funcionalidades, com marketplace visual
- 🛡️ **Segurança**: Projetado com segurança e compliance em mente, com dashboard de monitoramento
- 📊 **Observabilidade**: Monitoramento completo e métricas detalhadas através de interfaces visuais intuitivas
- 🌍 **Multilinguagem**: Crie componentes em Java, JavaScript ou Python e integre-os seamlessly no mesmo fluxo
- 🖥️ **Visual First**: Design, configuração e monitoramento totalmente visual, sem necessidade de codificação para operações comuns

## 🎯 O que você pode construir?

O archflow permite criar soluções de IA complexas através de plugins especializados:

### 🤖 Assistentes e Agentes IA
- Análise de Sentimento e Intenção
- Classificação e Categorização de Textos
- Sumarização de Documentos
- Assistentes Especializados (Jurídico, Médico, Financeiro)
- Atendimento ao Cliente Automatizado

### 📚 Processamento de Conhecimento
- RAG (Retrieval Augmented Generation)
- Integração com Bases de Conhecimento
- Processamento de PDFs e Documentos
- Web Scraping e Análise
- Gestão de Memória e Contexto

### 🔒 Segurança e Compliance
- Detecção de Viés
- Filtragem de Conteúdo
- Validação de Fatos
- Detecção de Alucinações
- Análise de Toxicidade

### 🔗 Integrações
- Conexão com CRMs e ERPs
- APIs REST e GraphQL
- Bancos de Dados e Data Lakes
- Sistemas Legados
- Plataformas de Mensageria

### 📊 Analytics e Relatórios
- Geração de Documentos
- Formatação de Respostas
- Geração de Relatórios
- Análise de Dados
- Dashboards Interativos

### 🛠️ Ferramentas Especializadas
- Calculadoras Avançadas
- Processamento de Datas
- Geração de Código
- Tradução Especializada
- Análise de Imagens

## 🔌 Plugins Disponíveis

### 1. Modelos de IA
- Plugin GPT-4: Integração com OpenAI
- Plugin Claude: Integração com Anthropic
- Plugin Mistral: Acesso aos modelos Mistral AI
- Plugin Local LLM: Execução de modelos locais

### 2. Processamento de Linguagem
- Análise de Sentimento
- Classificação de Texto
- Extração de Entidades
- Sumarização de Documentos
- Análise de Intenção

### 3. RAG (Retrieval Augmented Generation)
- Plugin Weaviate: Busca vetorial
- Plugin pgvector: Armazenamento de embeddings
- Plugin ChromaDB: Gestão de documentos
- Plugin PDF: Extração de texto
- Plugin Web Scraping: Coleta de dados

### 4. Memória e Contexto
- Plugin Redis: Cache e memória de curto prazo
- Plugin PostgreSQL: Armazenamento persistente
- Plugin de Sessão: Gestão de contexto
- Plugin de Histórico: Tracking de interações

### 5. Especialidades
- Plugin Jurídico: Conhecimento legal
- Plugin Médico: Terminologia médica
- Plugin Financeiro: Análise financeira
- Plugin de Atendimento: Suporte ao cliente

### 6. Validação e Segurança
- Detector de Viés
- Filtro de Conteúdo Impróprio
- Validador de Fatos
- Detector de Alucinações
- Análise de Toxicidade

### 7. Integração de Dados
- Conexão com CRM
- Integração com ERP
- Acesso a Bases de Conhecimento
- Integração com Wikis
- Conexão com Bases de Dados

### 8. Saída e Formatação
- Geração de Documentos
- Formatação de Respostas
- Geração de Relatórios
- Conversão de Formatos
- Templates de Resposta

### 9. Monitoramento
- Tracking de Uso de Tokens
- Análise de Performance
- Monitoramento de Qualidade
- Avaliação de Respostas
- Feedback Loop

### 10. Ferramentas Específicas
- Calculadora Avançada
- Processamento de Datas
- Geração de Código
- Tradução Especializada
- Análise de Imagens

Todos estes componentes podem ser:
- Criados e configurados visualmente ou via código (Java, JavaScript, Python)
- Combinados em fluxos complexos
- Personalizados para necessidades específicas
- Compartilhados via marketplace
- Monitorados em tempo real

## 🚀 Início Rápido

### Dependência Maven
```xml
<dependency>
    <groupId>br.com.archflow</groupId>
    <artifactId>archflow-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Exemplo Simples
```java
@AIFlow
public class CustomerSupportFlow {
    @FlowStep(order = 1)
    public StepResult analyzeIntent(String customerMessage) {
        return StepResult.of(Map.of(
            "intent", "technical-support",
            "priority", "high"
        ));
    }

    @FlowStep(order = 2)
    public StepResult generateResponse(Map<String, Object> analysis) {
        return StepResult.of(
            "Entendi que você precisa de suporte técnico. Como posso ajudar?"
        );
    }
}
```

➡️ [Veja mais exemplos](docs/examples/README.md)

## 📦 Módulos

- **archflow-core**: Contratos e interfaces principais
- **archflow-model**: Modelos de domínio
- **archflow-plugin-api**: API para desenvolvimento de plugins
- **archflow-engine**: Motor de execução baseado em Camel
- **archflow-api**: REST API para gerenciamento
- **archflow-agent**: Agente de execução distribuída
- **archflow-langchain4j**: Integração com LangChain4j
- **archflow-plugin-loader**: Carregamento dinâmico e gestão de plugins em runtime


## 🛠️ Recursos

- Interface visual para design de fluxos
- Suporte a múltiplos LLMs
- Sistema robusto de plugins
- Execução distribuída
- Métricas e monitoramento
- Cache e otimização

## 🌱 Começando

1. **Setup do Ambiente**
   ```bash
   git clone https://github.com/archflow/archflow.git
   cd archflow
   mvn clean install
   ```

2. **Primeiro Fluxo**
   - [Guia de Início Rápido](docs/development/quickstart.md)
   - [Tutoriais](docs/tutorials/README.md)
   - [Exemplos](docs/examples/README.md)

## 🤝 Contribuindo

Adoramos contribuições! Veja como você pode ajudar:

- 🐛 Reporte bugs e sugira features nas [Issues](https://github.com/archflow/archflow/issues)
- 📖 Melhore a [documentação](docs/README.md)
- 💻 Contribua com código seguindo nosso [guia](docs/development/contributing.md)
- 🌟 Dê uma estrela no projeto e compartilhe!

## 📚 Links

- [Website](https://archflow.github.io)
- [Documentação](docs/README.md)
- [Discord](https://discord.gg/archflow)
- [Blog](https://blog.archflow.org)

## 📄 Licença

archflow é licenciado sob [Apache License 2.0](LICENSE).

## 🙏 Agradecimentos

- [LangChain4j](https://github.com/langchain4j/langchain4j) - Base para processamento de IA
- [Apache Camel](https://camel.apache.org/) - Engine de integração
- [Contribuidores](https://github.com/archflow/archflow/graphs/contributors)

---

<div align="center">
⭐️ Se você gosta do archflow, considere dar uma estrela no projeto! ⭐️

[Comece Agora](docs/development/quickstart.md) • [Aprenda Mais](docs/README.md) • [Contribua](docs/development/contributing.md)
</div>