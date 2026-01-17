---
title: LangChain4j API
sidebar_position: 3
slug: api-langchain4j
---

# LangChain4j API

Integração com LangChain4j para modelos de linguagem e embeddings.

## ChatLanguageModel

Interface para modelos de chat.

### Criação

```java
// OpenAI
ChatLanguageModel model = OpenAiChatModel.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .modelName("gpt-4o")
    .temperature(0.7)
    .timeout(Duration.ofSeconds(30))
    .build();

// Anthropic Claude
ChatLanguageModel model = AnthropicChatModel.builder()
    .apiKey(System.getenv("ANTHROPIC_API_KEY"))
    .modelName("claude-3-5-sonnet-20241022")
    .temperature(0.7)
    .build();

// Azure OpenAI
ChatLanguageModel model = AzureOpenAiChatModel.builder()
    .endpoint("https://your-resource.openai.azure.com")
    .apiKey(System.getenv("AZURE_API_KEY"))
    .deploymentName("gpt-4")
    .build();
```

### Métodos

```java
// Geração simples
String response = model.generate("Olá!");

// Com mensagens
List<ChatMessage> messages = List.of(
    SystemMessage.from("Você é um assistente útil"),
    UserMessage.from("Qual a capital do Brasil?")
);
String response = model.generate(messages);

// Com múltiplas respostas (n > 1)
List<String> responses = model.generate("Explique IA", 3);

// Streaming
model.generate("Conte uma história", new StreamingResponseHandler() {
    @Override
    public void onPartial(String partial) {
        System.out.print(partial);
    }

    @Override
    public void onComplete(Response complete) {
        System.out.println("\nCompleto!");
    }
});
```

## StreamingChatLanguageModel

Modelo com suporte a streaming.

```java
StreamingChatLanguageModel model = OpenAiStreamingChatModel.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .modelName("gpt-4o")
    .build();

// Streaming com Reactor
Flux<String> stream = model.generate("Explique quantum computing");

stream.subscribe(
    chunk -> System.out.print(chunk),
    error -> error.printStackTrace(),
    () -> System.out.println("\nFim!")
);

// Streaming com callback
model.generate("Texto", new StreamingResponseHandler() {
    @Override
    public void onPartial(String partial) {
        // Chunk recebido
    }

    @Override
    public void onComplete(Response complete) {
        // Completo
    }
});
```

## EmbeddingModel

Modelo para criação de embeddings.

### Criação

```java
// OpenAI
EmbeddingModel model = OpenAiEmbeddingModel.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .modelName("text-embedding-3-small")
    .dimension(1536)
    .build();

// HuggingFace
EmbeddingModel model = HuggingFaceEmbeddingModel.builder()
    .accessToken(System.getenv("HF_TOKEN"))
    .modelId("sentence-transformers/all-MiniLM-L6-v2")
    .build();
```

### Uso

```java
// Embedding único
ResponseEmbedding embedding = model.embed("Texto para embed");
float[] vector = embedding.vector();

// Múltiplos embeddings
List<Embedding> embeddings = model.embedAll(List.of(
    "Texto 1",
    "Texto 2",
    "Texto 3"
));

// Similaridade
float similarity = cosSimilarity(
    embedding1.vector(),
    embedding2.vector()
);
```

## ImageModel

Modelo para geração de imagens.

```java
ImageModel model = OpenAiImageModel.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .modelName("dall-e-3")
    .build();

GeneratedImage image = model.generate("Um gato astronauta");

// Salvar
Files.write(Path.of("cat.png"), image.content());

// URL
String url = image.url();
```

## Modality

Modelos multimodais (texto + imagem).

```java
ChatMessage message = UserMessage.from(
    ImageContent.from("https://example.com/image.png"),
    TextContent.from("Descreva esta imagem")
);

String response = model.generate(message);
```

## Tool Specification

Especificação de ferramentas para LangChain4j.

```java
ToolSpecification tool = ToolSpecification.builder()
    .name("weather")
    .description("Obtém previsão do tempo")
    .addParameter("city", Type.STRING)
    .addParameter("units", Type.STRING, enumValues("celsius", "fahrenheit"))
    .build();

// Uso no modelo
String response = model.generate(
    UserMessage.from("Qual o tempo em São Paulo?"),
    tool
);
```

## Spring Boot Auto-Configuration

Configuração automática com Spring Boot.

### application.yml

```yaml
langchain4j:
  open-ai:
    chat-model:
      api-key: ${OPENAI_API_KEY}
      model-name: gpt-4o
      temperature: 0.7
      max-tokens: 2000

    embedding-model:
      api-key: ${OPENAI_API_KEY}
      model-name: text-embedding-3-small
      dimension: 1536
```

### Injeção

```java
@Service
public class MyService {

    private final ChatLanguageModel chatModel;
    private final EmbeddingModel embeddingModel;

    public MyService(
            ChatLanguageModel chatModel,
            EmbeddingModel embeddingModel) {
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
    }

    public String chat(String message) {
        return chatModel.generate(message);
    }
}
```

## Providers Suportados

| Provider | Modelos de Chat | Embeddings |
|----------|-----------------|------------|
| **OpenAI** | ✅ | ✅ |
| **Anthropic** | ✅ | ❌ |
| **Azure OpenAI** | ✅ | ✅ |
| **Google Gemini** | ✅ | ✅ |
| **AWS Bedrock** | ✅ | ✅ |
| **Hugging Face** | ✅ | ✅ |
| **Ollama** | ✅ | ✅ |
| **Mistral AI** | ✅ | ✅ |
| **Cohere** | ✅ | ✅ |
| **DeepInfra** | ✅ | ✅ |
| **GitHub** | ✅ | ❌ |
| **Vertex AI** | ✅ | ✅ |
| **Workers AI** | ✅ | ✅ |
| **Zhipu AI** | ✅ | ✅ |
