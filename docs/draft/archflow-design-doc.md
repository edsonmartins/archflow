# Documento de Design - archflow

## 1. Visão Geral

O archflow é uma plataforma LowCode que permite criar, executar e gerenciar fluxos de trabalho baseados em IA de forma robusta e escalável. A plataforma atua como uma camada de abstração sobre o LangChain4j, permitindo que usuários configurem visualmente (ou via JSON) fluxos complexos de IA sem necessidade de codificação direta.

## 2. Arquitetura do Sistema

### 2.1 Componentes Principais

1. **Core Engine**
   - Responsável pela execução dos fluxos
   - Gerencia o ciclo de vida dos componentes
   - Mantém o estado e contexto da execução

2. **Component Catalog**
   - Registro de componentes disponíveis
   - Gerenciamento de versões
   - Sistema de plugins

3. **LangChain4j Integration**
   - Adaptadores para recursos do LangChain4j
   - Gerenciamento de modelos LLM
   - Integração com ferramentas e agentes

4. **Flow Manager**
   - Carregamento de fluxos
   - Validação de estrutura
   - Gerenciamento de estado

### 2.2 Estrutura de Módulos

```
archflow/
├── archflow-core/        # Contratos e interfaces base
├── archflow-model/       # Modelos de domínio
├── archflow-plugin-api/  # API de plugins
├── archflow-engine/      # Motor de execução
├── archflow-langchain4j/ # Integração com LangChain4j
└── archflow-agent/       # Agente de execução
```

## 3. Integração com LangChain4j

### 3.1 Adaptadores

Os adaptadores servem como ponte entre a configuração do usuário e as funcionalidades do LangChain4j:

```java
public interface LangChainAdapter {
    void configure(Map<String, Object> properties);
    Object execute(String operation, Object input, ExecutionContext context);
}

public class ChainAdapter implements LangChainAdapter {
    private Chain chain;
    
    @Override
    public void configure(Map<String, Object> properties) {
        // Configura a openai baseado nas properties
        this.chain = createChain(properties);
    }
    
    @Override
    public Object execute(String operation, Object input, ExecutionContext context) {
        return chain.execute(input);
    }
}
```

### 3.2 Componentes Pré-configurados

Componentes podem ser registrados com metadados descritivos:

```java
@ComponentDescriptor(
    id = "sales-assistant",
    name = "Sales Assistant",
    type = ComponentType.ASSISTANT,
    version = "1.0.0",
    operations = {
        @Operation(
            id = "analyzeRequest",
            name = "Analyze Customer Request",
            inputs = {
                @Property(id = "message", name = "Customer Message", type = "string")
            }
        )
    }
)
public class SalesAssistantComponent implements AIComponent {
    private final LangChainAdapter adapter;
    
    @Override
    public void initialize(Map<String, Object> config) {
        adapter.configure(config);
    }
}
```

## 4. Definição de Fluxos

### 4.1 Estrutura JSON

Os fluxos são definidos em JSON com suporte a múltiplos caminhos e execução paralela:

```json
{
  "steps": [
    {
      "id": "analyze",
      "componentId": "sales-assistant",
      "operation": "analyzeRequest",
      "parameters": {
        "message": "{input.message}"
      },
      "targets": [
        {
          "condition": "analysis.intent == 'order_request'",
          "stepIds": ["extract_order"]
        },
        {
          "condition": "analysis.intent == 'price_query'",
          "stepIds": ["product_search", "price_lookup"]
        }
      ]
    }
  ]
}
```

### 4.2 Parâmetros e Referências

- Suporte a referências dinâmicas: `{stepId.output.field}`
- Condições de roteamento
- Execução paralela
- Agregação de resultados

## 5. Execução de Fluxos

### 5.1 Processo de Execução

1. **Carregamento**
   - Carrega definição do fluxo
   - Valida estrutura e referências
   - Inicializa componentes necessários

2. **Execução**
   - Processa cada passo sequencialmente
   - Avalia condições de roteamento
   - Gerencia execuções paralelas
   - Mantém contexto e estado

3. **Monitoramento**
   - Coleta métricas
   - Registra logs
   - Mantém histórico de execução

### 5.2 Código de Execução

```java
public FlowResult executeFlow(Flow flow, ExecutionContext context) {
    try {
        FlowStep currentStep = flow.getSteps().get(0);
        
        while (currentStep != null) {
            // Executa o passo atual
            StepResult stepResult = executeStep(currentStep, context);
            
            // Avalia próximos passos
            List<String> nextStepIds = evaluateTargets(
                currentStep, 
                stepResult, 
                context
            );
            
            // Execução paralela se necessário
            if (nextStepIds.size() > 1) {
                List<StepResult> results = executeParallelSteps(
                    nextStepIds, 
                    flow, 
                    context
                );
                aggregateResults(results, context);
            }
            
            // Determina próximo passo
            currentStep = determineNextStep(flow, nextStepIds);
        }

        return createFlowResult(context);
    } catch (Exception e) {
        handleExecutionError(flow.getId(), e);
        return createErrorResult(e);
    }
}
```

## 6. Exemplo: Assistente de Vendas

### 6.1 Descrição

O Assistente de Vendas é um exemplo de aplicação que demonstra as capacidades do archflow:

- Processamento de linguagem natural
- Integração com bases de conhecimento
- Consulta de preços e produtos
- Geração de respostas contextualizadas

### 6.2 Componentes Necessários

1. **SalesAssistant**
   - Análise de intenção
   - Geração de respostas
   - Gerenciamento de contexto

2. **OrderExtractor**
   - Extração de produtos
   - Identificação de quantidades
   - Normalização de unidades

3. **ProductCatalog**
   - Busca de produtos
   - Consulta de preços
   - Validação de disponibilidade

### 6.3 Configuração do Fluxo

```json
{
  "components": {
    "assistant": {
      "type": "sales-assistant",
      "config": {
        "llm": {
          "provider": "openai",
          "model": "gpt-4"
        }
      }
    },
    "extractor": {
      "type": "order-extractor",
      "config": {
        "extractionPrompt": "..."
      }
    },
    "catalog": {
      "type": "product-catalog",
      "config": {
        "weaviate": {
          "url": "http://localhost:8080"
        }
      }
    }
  }
}
```

## 7. Próximos Passos

1. **Implementação da Integração LangChain4j**
   - Desenvolvimento dos adaptadores
   - Testes de integração
   - Documentação de uso

2. **Desenvolvimento de Componentes**
   - Criação de componentes base
   - Testes de funcionalidade
   - Exemplos de uso

3. **Implementação do Agente**
   - Sistema de execução
   - Gerenciamento de estado
   - Monitoramento

4. **Documentação e Exemplos**
   - Guias de uso
   - Tutoriais
   - Casos de uso