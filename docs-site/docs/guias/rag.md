---
title: RAG (Retrieval-Augmented Generation)
sidebar_position: 3
slug: rag
---

# Implementando RAG

RAG (Retrieval-Augmented Generation) combina busca de informações com geração de texto para criar respostas baseadas em conhecimento específico.

## Arquitetura RAG

```
┌─────────────────────────────────────────────────────────────┐
│  User Query                                                 │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│  Embedding Model (converte query em vetor)                  │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│  Vector Store (busca documentos similares)                 │
│  - Pinecone, Weaviate, Chroma, Milvus, pgvector           │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│  Context Assembly (query + documentos)                      │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│  LLM Generation (resposta com contexto)                     │
└─────────────────────────────────────────────────────────────┘
```

## Guia: Sistema de Perguntas e Respostas

### Passo 1: Configure o Vector Store

```yaml
# application.yml
archflow:
  vector-store:
    type: weaviate
    url: http://localhost:8080
    api-key: ${WEAVIATE_API_KEY}

  embedding:
    provider: openai
    model: text-embedding-3-small
```

### Passo 2: Crie o Ingestor de Documentos

```java
@Service
public class DocumentIngestor {

    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;

    public void ingestDocument(String id, String content, Map<String, Object> metadata) {
        // Divide em chunks
        List<String> chunks = splitIntoChunks(content, 500);

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);

            // Cria embedding
            float[] embedding = embeddingModel.embed(chunk);

            // Salva no vector store
            Document document = Document.builder()
                .id(id + "-" + i)
                .content(chunk)
                .embedding(embedding)
                .metadata(merge(metadata, Map.of(
                    "chunkIndex", i,
                    "totalChunks", chunks.size()
                )))
                .build();

            vectorStore.add(document);
        }
    }

    private List<String> splitIntoChunks(String text, int maxTokens) {
        // Implementação de chunking inteligente
        return List.of(text.split("(?<=\\.)\\s+"));
    }
}
```

### Passo 3: Crie o Agente RAG

```java
@Service
public class RagService {

    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;
    private final ChatLanguageModel llm;

    public String query(String question) {
        // 1. Embed da pergunta
        float[] queryEmbedding = embeddingModel.embed(question);

        // 2. Busca documentos similares
        List<Document> relevantDocs = vectorStore.search(
            queryEmbedding,
            5,  // top-k
            0.7 // similarity threshold
        );

        // 3. Monta o contexto
        String context = relevantDocs.stream()
            .map(Document::getContent)
            .collect(Collectors.joining("\n\n"));

        // 4. Gera resposta
        String prompt = String.format("""
            Use o seguinte contexto para responder à pergunta:

            Contexto:
            %s

            Pergunta: %s

            Resposta:
            """, context, question);

        return llm.generate(prompt);
    }
}
```

### Passo 4: Workflow RAG

```java
@Component
public class RagWorkflow {

    private final FlowEngine flowEngine;
    private final RagService ragService;

    @PostConstruct
    public void createWorkflow() {
        Workflow workflow = Workflow.builder()
            .id("rag-query")
            .nodes(List.of(
                InputNode.builder()
                    .id("input")
                    .field("question", "Sua pergunta")
                    .build(),

                Node.builder()
                    .id("retrieval")
                    .type("retrieval")
                    .config(Map.of(
                        "topK", 5,
                        "threshold", 0.7
                    ))
                    .build(),

                LLMNode.builder()
                    .id("generator")
                    .prompt("""
                        Use o contexto para responder:

                        {retrieval.context}

                        Pergunta: {input.question}
                        """)
                    .build(),

                OutputNode.builder()
                    .id("output")
                    .template("${generator.output}")
                    .build()
            ))
            .edges(List.of(
                Edge.from("input").to("retrieval"),
                Edge.from("retrieval").to("generator"),
                Edge.from("generator").to("output")
            ))
            .build();

        flowEngine.register(workflow);
    }
}
```

## Vector Stores Suportados

| Provider | Descrição | Configuração |
|----------|-----------|--------------|
| **Weaviate** | Vector store open-source | `weaviate://localhost:8080` |
| **Pinecone** | Vector store gerenciado | `pinecone:${API_KEY}` |
| **Chroma** | Vector store local/embedded | `chroma://./data` |
| **Milvus** | Vector store distribuído | `milvus://localhost:19530` |
| **pgvector** | PostgreSQL + vector | `postgresql://localhost/db` |
| **Redis** | Redis Stack | `redis://localhost:6379` |

## Ingestão de Dados

### PDF

```java
@Service
public class PdfIngestor {

    public void ingestPdf(String filePath) {
        String text = PdfExtractor.extract(filePath);
        ingestDocument("doc-" + UUID.randomUUID(), text, Map.of(
            "source", filePath,
            "type", "pdf"
        ));
    }
}
```

### Website

```java
@Service
public class WebScraper {

    public void ingestWebsite(String url) {
        String content = Jsoup.connect(url).get().text();
        ingestDocument(url, content, Map.of(
            "source", url,
            "type", "web"
        ));
    }
}
```

### Database

```java
@Service
public class DatabaseIngestor {

    public void ingestTable(String tableName) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT * FROM " + tableName
        );

        for (Map<String, Object> row : rows) {
            String content = row.toString();
            ingestDocument(tableName + "-" + row.get("id"), content, Map.of(
                "table", tableName,
                "id", row.get("id")
            ));
        }
    }
}
```

## Melhorando a Qualidade

### Re-ranking

```java
List<Document> reranked = CrossEncoder.rerank(
    question,
    retrievedDocs,
    10 // top-n after reranking
);
```

### Hybrid Search

```java
// Combina busca semântica com busca lexical
List<Document> results = HybridSearch.builder()
    .semanticWeight(0.7)
    .keywordWeight(0.3)
    .search(query);
```

### Metadata Filtering

```java
List<Document> results = vectorStore.search(
    embedding,
    Filter.builder()
        .eq("category", "support")
        .gte("date", "2025-01-01")
        .build()
);
```

## Próximos Passos

- [multi-agente](/docs/guias/multi-agente) - Combine RAG com múltiplos agentes
