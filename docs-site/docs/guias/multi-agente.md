---
title: Multi-Agente
sidebar_position: 4
slug: multi-agente
---

# Sistema Multi-Agente

Coordene múltiplos agentes AI especializados trabalhando juntos para resolver tarefas complexas.

## Arquitetura Multi-Agente

```
┌─────────────────────────────────────────────────────────────┐
│                     Supervisor                               │
│  - Decide qual agente chamar                               │
│  - Coordena a execução                                     │
│  - Agrega resultados                                       │
└─────────────────────────────────────────────────────────────┘
         ↓                ↓                ↓
┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│  Research    │  │   Writer     │  │  Reviewer    │
│   Agent      │  │   Agent      │  │   Agent      │
└──────────────┘  └──────────────┘  └──────────────┘
```

## Padrões de Coordenação

### 1. Sequencial

Agentes executam em ordem, cada um recebendo a saída do anterior:

```java
AgentSupervisor supervisor = AgentSupervisor.builder()
    .mode(CoordinationMode.SEQUENTIAL)
    .agents(List.of(
        researchAgent,
        writerAgent,
        reviewerAgent
    ))
    .build();

String result = supervisor.coordinate(
    "Crie um artigo sobre arquitetura de microserviços"
);
```

### 2. Paralelo

Múltiplos agentes executam simultaneamente:

```java
AgentSupervisor supervisor = AgentSupervisor.builder()
    .mode(CoordinationMode.PARALLEL)
    .agents(List.of(
        webSearchAgent,
        databaseAgent,
        apiAgent
    ))
    .aggregator(Aggregator.mergeAll())
    .build();
```

### 3. Hierárquico

Um supervisor delega para sub-agentes:

```java
AgentSupervisor supervisor = AgentSupervisor.builder()
    .mode(CoordinationMode.HIERARCHICAL)
    .systemPrompt("""
        Você é um coordenador de equipe.
        Delegue tarefas aos agentes especializados apropriados.
        """)
    .agents(List.of(
        researchAgent,
        writerAgent,
        reviewerAgent
    ))
    .build();
```

## Guia: Pipeline de Criação de Conteúdo

### Passo 1: Defina os Agentes Especializados

```java
@Configuration
public class ContentAgents {

    // Agente de Pesquisa
    @Bean
    public Agent researchAgent(EmbeddingModel embedding, VectorStore vectorStore) {
        return Agent.builder()
            .id("researcher")
            .name("Pesquisador")
            .systemPrompt("""
                Você é um pesquisador especializado.
                Sua tarefa é buscar informações relevantes sobre o tópico.
                Use a ferramenta RAG para encontrar documentos.
                """)
            .tools(List.of(new RagTool(embedding, vectorStore)))
            .build();
    }

    // Agente Escritor
    @Bean
    public Agent writerAgent() {
        return Agent.builder()
            .id("writer")
            .name("Escritor")
            .systemPrompt("""
                Você é um escritor profissional.
                Use as informações da pesquisa para criar conteúdo envolvente.
                Siga o estilo e tom solicitado.
                """)
            .build();
    }

    // Agente Revisor
    @Bean
    public Agent reviewerAgent() {
        return Agent.builder()
            .id("reviewer")
            .name("Revisor")
            .systemPrompt("""
                Você é um editor experiente.
                Revise o conteúdo para:
                - Gramática e ortografia
                - Clareza e fluidez
                - Coerência estrutural
                """)
            .build();
    }
}
```

### Passo 2: Crie o Supervisor

```java
@Service
public class ContentPipeline {

    private final AgentSupervisor supervisor;

    public ContentPipeline(
            Agent researchAgent,
            Agent writerAgent,
            Agent reviewerAgent) {

        this.supervisor = AgentSupervisor.builder()
            .id("content-pipeline")
            .mode(CoordinationMode.SEQUENTIAL)
            .agents(List.of(researchAgent, writerAgent, reviewerAgent))
            .build();
    }

    public String createArticle(String topic, String style) {
        String prompt = String.format("""
            Crie um artigo sobre "%s" no estilo %s.

            Pipeline:
            1. Researcher: Busque informações relevantes
            2. Writer: Crie o artigo com as informações
            3. Reviewer: Revise e sugira melhorias
            """, topic, style);

        return supervisor.coordinate(prompt);
    }
}
```

### Passo 3: Rastreamento de Execução

```java
@Service
public class TracingPipeline {

    public String createWithTracing(String topic) {
        MultiAgentResult result = supervisor.coordinateWithTracing(topic);

        // Resultado agregado
        String finalOutput = result.getFinalOutput();

        // Detalhes da execução
        for (AgentExecution exec : result.getExecutions()) {
            log.info("Agente {}: {}ms",
                exec.getAgentId(),
                exec.getDuration());
        }

        return finalOutput;
    }
}
```

## Padrões Avançados

### Agente com Memória Compartilhada

```java
AgentSupervisor.builder()
    .sharedMemory(SharedMemory.builder()
        .maxSize(1000)
        .persistence(true)
        .build())
    .build();
```

### Agente com Verificação

```java
Agent supervisor = Agent.builder()
    .id("supervisor")
    .verifier((output) -> {
        // Verifica se a saída atende critérios
        return output.contains("## Referências") &&
               output.split("\\s+").length >= 500;
    })
    .maxRetries(3)
    .build();
```

### Agente com Votação

```java
AgentSupervisor.builder()
    .mode(CoordinationMode.CONSENSUS)
    .agents(List.of(
        agent1, agent2, agent3
    ))
    .aggregator(Aggregator.majorityVote())
    .build();
```

## Casos de Uso

### Análise Multi-perspectiva

```java
List<Agent> analysts = List.of(
    createAgent("analista-otimista", "Foque em oportunidades"),
    createAgent("analista-pessimista", "Foque em riscos"),
    createAgent("analista-neutro", "Visão equilibrada")
);

AgentSupervisor supervisor = AgentSupervisor.builder()
    .agents(analysts)
    .aggregator(Aggregator.synthesize())
    .build();

String analysis = supervisor.coordinate("Analisar investimento X");
```

### Resolução de Problemas

```java
AgentSupervisor.builder()
    .agents(List.of(
        diagnosticAgent,    // Diagnostica o problema
        solutionAgent,      // Propõe soluções
        validationAgent     // Valida a solução
    ))
    .feedbackLoop(true)    // Permite iterações
    .build();
```

## Monitoramento

```java
@Component
public class MultiAgentMetrics {

    private final MeterRegistry registry;

    public void recordExecution(AgentExecution exec) {
        registry.timer("agent.execution",
            "agent", exec.getAgentId(),
            "status", exec.getStatus().name())
            .record(exec.getDuration(), TimeUnit.MILLISECONDS);
    }
}
```

## Próximos Passos

- [Observabilidade](/docs/integracoes/observabilidade) - Monitoramento e métricas
- [MCP](/docs/integracoes/mcp) - Integração com MCP para interoperabilidade
