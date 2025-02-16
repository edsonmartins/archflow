# Guia de Início Rápido

Este guia irá ajudá-lo a começar com o archflow em poucos minutos.

## Pré-requisitos

- Java 17+
- Maven 3.8+
- Docker (opcional, para execução de alguns componentes)

## Instalação

### 1. Adicione a Dependência

```xml
<dependency>
    <groupId>br.com.archflow</groupId>
    <artifactId>archflow-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. Configure o LangChain4j

```properties
langchain4j.openai.api-key=seu-api-key
langchain4j.openai.model-name=gpt-4-turbo
```

## Seu Primeiro Fluxo

### 1. Crie um Fluxo Simples

```java
import br.com.archflow.core.annotation.AIFlow;
import br.com.archflow.core.annotation.FlowStep;
import br.com.archflow.core.model.StepResult;

@AIFlow
public class CustomerSupportFlow {
    
    @FlowStep(order = 1)
    public StepResult analyzeIntent(String customerMessage) {
        return StepResult.of(Map.of(
            "intent", "Análise do problema do cliente",
            "sentiment", "neutral"
        ));
    }
    
    @FlowStep(order = 2)
    public StepResult generateResponse(Map<String, Object> analysis) {
        return StepResult.of(
            "Entendi sua necessidade. Como posso ajudar?"
        );
    }
}
```

### 2. Configure o Engine

```java
@Configuration
public class FlowConfig {
    
    @Bean
    public FlowEngine flowEngine(
        LangChain4j langChain,
        FlowValidator validator
    ) {
        return FlowEngine.builder()
            .langChain(langChain)
            .validator(validator)
            .build();
    }
}
```

### 3. Execute o Fluxo

```java
@Service
public class SupportService {
    
    private final FlowEngine flowEngine;
    
    public CompletableFuture<FlowResult> handleCustomerMessage(String message) {
        return flowEngine.execute(
            new CustomerSupportFlow(),
            ExecutionContext.builder()
                .input(message)
                .build()
        );
    }
}
```

## Usando Plugins

### 1. Adicione um Plugin

```java
@PluginDescriptor(
    id = "sentiment-analyzer",
    name = "Sentiment Analyzer",
    operations = {
        @PluginOperation(
            name = "analyze",
            description = "Analisa sentimento do texto"
        )
    }
)
public class SentimentPlugin implements AIPlugin {
    
    @Override
    public Object execute(String operationId, Object input, Map<String, Object> context) {
        // Implementação
        return Map.of("sentiment", "positive");
    }
}
```

### 2. Use no Fluxo

```java
@AIFlow
public class EnhancedSupportFlow {
    
    @Inject
    private SentimentPlugin sentimentPlugin;
    
    @FlowStep(order = 1)
    public StepResult analyzeSentiment(String message) {
        return StepResult.of(
            sentimentPlugin.execute("analyze", message, Map.of())
        );
    }
}
```

## Monitoramento

### 1. Adicione Métricas

```java
@Component
public class FlowMetrics {
    
    private final MeterRegistry registry;
    
    public void recordExecution(FlowResult result) {
        registry.timer("flow.execution")
            .record(result.getExecutionTime());
            
        registry.counter("flow.status." + result.getStatus())
            .increment();
    }
}
```

### 2. Visualize no Dashboard

Acesse `http://localhost:8080/actuator/metrics` para ver as métricas.

## Debug e Logs

### 1. Configure Logging

```properties
logging.level.br.com.archflow=DEBUG
```

### 2. Veja os Logs

```log
2024-02-16 10:00:00.000  DEBUG [archflow] --- Starting flow execution: customer-support
2024-02-16 10:00:00.100  DEBUG [archflow] --- Step 'analyzeIntent' completed
2024-02-16 10:00:00.200  DEBUG [archflow] --- Flow completed successfully
```

## Próximos Passos

1. [Explore a Documentação Completa](../README.md)
2. [Veja Exemplos Práticos](../tutorials/examples)
3. [Aprenda sobre Plugins](../reference/plugins)
4. [Configure para Produção](../deployment)

## Problemas Comuns

### 1. Erro de Conexão com LLM
```
Verifique sua API key e conexão com internet.
Certifique-se que o modelo solicitado está disponível.
```

### 2. Falha na Validação do Fluxo
```
Verifique a ordem dos steps.
Certifique-se que todos os inputs necessários estão presentes.
```

### 3. Erro de Memória
```
Ajuste as configurações de memória da JVM.
Verifique se há memory leaks nos seus fluxos.
```

## Suporte

Precisa de ajuda?
- [GitHub Issues](https://github.com/archflow/archflow/issues)
- [Discord](https://discord.gg/archflow)
- [Stack Overflow](https://stackoverflow.com/questions/tagged/archflow)