package br.com.archflow.template.knowledge;

import br.com.archflow.model.Workflow;
import br.com.archflow.template.AbstractWorkflowTemplate;
import br.com.archflow.template.WorkflowTemplateDefinition;

import java.util.*;

/**
 * Template for knowledge base workflows with RAG (Retrieval-Augmented Generation).
 *
 * <p>This template provides intelligent knowledge base querying with:
 * <ul>
 *   <li><b>Vector Search:</b> Semantic search in document embeddings</li>
 *   <li><b>Query Expansion:</b> Improve query with related terms</li>
 *   <li><b>Reranking:</b> Reorder results by relevance</li>
 *   <li><b>Citation:</b> Source attribution for answers</li>
 *   <li><b>Hybrid Search:</b> Combine vector and keyword search</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * KnowledgeBaseTemplate template = new KnowledgeBaseTemplate();
 *
 * Map<String, Object> params = Map.of(
 *     "vectorStoreId", "my-kb",
 *     "topK", 5,
 *     "includeCitations", true
 * );
 *
 * Workflow workflow = template.createInstance("kb-chat", params);
 * }</pre>
 */
public class KnowledgeBaseTemplate extends AbstractWorkflowTemplate {

    private static final String ID = "knowledge-base";
    private static final String VERSION = "1.0.0";

    public KnowledgeBaseTemplate() {
        super(
                ID,
                "Knowledge Base with RAG",
                "RAG-powered knowledge base for intelligent document Q&A with citations",
                "knowledge",
                Set.of("rag", "knowledge-base", "search", "qa", "retrieval"),
                buildParameters()
        );
    }

    private static Map<String, ParameterDefinition> buildParameters() {
        Map<String, ParameterDefinition> params = new LinkedHashMap<>();

        params.put("vectorStoreId", ParameterDefinition.required(
                "vectorStoreId",
                "ID of the vector store containing the knowledge base",
                String.class
        ));

        params.put("topK", ParameterDefinition.optional(
                "topK",
                "Number of documents to retrieve",
                Integer.class,
                5
        ));

        params.put("minScore", ParameterDefinition.optional(
                "minScore",
                "Minimum similarity score for retrieval",
                Double.class,
                0.7
        ));

        params.put("includeCitations", ParameterDefinition.optional(
                "includeCitations",
                "Include source citations in responses",
                Boolean.class,
                true
        ));

        params.put("hybridSearch", ParameterDefinition.optional(
                "hybridSearch",
                "Enable hybrid search (vector + keyword)",
                Boolean.class,
                true
        ));

        params.put("keywordWeight", ParameterDefinition.optional(
                "keywordWeight",
                "Weight for keyword search in hybrid mode (0.0-1.0)",
                Double.class,
                0.3
        ));

        params.put("rerankResults", ParameterDefinition.optional(
                "rerankResults",
                "Rerank retrieved results for better relevance",
                Boolean.class,
                true
        ));

        params.put("maxResponseLength", ParameterDefinition.optional(
                "maxResponseLength",
                "Maximum length of response in tokens",
                Integer.class,
                1024
        ));

        params.put("answerStyle", ParameterDefinition.enumParameter(
                "answerStyle",
                "Style of answer generation",
                "concise", "detailed", "conversational"
        ));

        return params;
    }

    @Override
    public WorkflowTemplateDefinition getDefinition() {
        Map<String, WorkflowTemplateDefinition.TemplateNode> nodes = new LinkedHashMap<>();

        // Query input node
        nodes.put("input", new WorkflowTemplateDefinition.TemplateNode(
                "input",
                "query-input",
                "User Query Input",
                Map.of(
                        "schema", Map.of(
                                "query", "string",
                                "context", "string",
                                "sessionId", "string"
                        )
                )
        ));

        // Query expansion node
        nodes.put("query-expander", new WorkflowTemplateDefinition.TemplateNode(
                "query-expander",
                "query-expansion",
                "Query Expansion",
                Map.of(
                        "expansions", 3,
                        "method", "llm"
                )
        ));

        // Vector search node
        nodes.put("vector-search", new WorkflowTemplateDefinition.TemplateNode(
                "vector-search",
                "vector-search",
                "Vector Search",
                Map.of(
                        "vectorStoreId", "{{vectorStoreId}}",
                        "topK", "{{topK}}",
                        "minScore", "{{minScore}}"
                )
        ));

        // Keyword search node
        nodes.put("keyword-search", new WorkflowTemplateDefinition.TemplateNode(
                "keyword-search",
                "keyword-search",
                "Keyword Search",
                Map.of(
                        "vectorStoreId", "{{vectorStoreId}}",
                        "topK", "{{topK}}",
                        "fuzziness", "AUTO"
                )
        ));

        // Result fusion node
        nodes.put("result-fusion", new WorkflowTemplateDefinition.TemplateNode(
                "result-fusion",
                "result-fusion",
                "Result Fusion",
                Map.of(
                        "vectorWeight", 0.7,
                        "keywordWeight", "{{keywordWeight}}",
                        "method", "rrf" // Reciprocal Rank Fusion
                )
        ));

        // Reranker node
        nodes.put("reranker", new WorkflowTemplateDefinition.TemplateNode(
                "reranker",
                "reranker",
                "Result Reranker",
                Map.of(
                        "model", "cross-encoder",
                        "topK", "{{topK}}"
                )
        ));

        // Context builder node
        nodes.put("context-builder", new WorkflowTemplateDefinition.TemplateNode(
                "context-builder",
                "context-builder",
                "Context Builder",
                Map.of(
                        "maxContextLength", 4000,
                        "truncate", "middle"
                )
        ));

        // Answer generator node
        nodes.put("answer-generator", new WorkflowTemplateDefinition.TemplateNode(
                "answer-generator",
                "llm",
                "Answer Generator",
                Map.of(
                        "promptTemplate", """
                                Answer the user's question using the context below.
                                If the answer cannot be found in the context, say "I don't have enough information to answer this."

                                Context:
                                {{context}}

                                Question: {{query}}

                                Provide a helpful, accurate answer.
                                """,
                        "temperature", 0.3,
                        "maxTokens", "{{maxResponseLength}}"
                )
        ));

        // Citation formatter node
        nodes.put("citation-formatter", new WorkflowTemplateDefinition.TemplateNode(
                "citation-formatter",
                "citation-formatter",
                "Citation Formatter",
                Map.of(
                        "format", "markdown",
                        "includeUrl", true
                )
        ));

        // Output node
        nodes.put("output", new WorkflowTemplateDefinition.TemplateNode(
                "output",
                "output",
                "Answer Output",
                Map.of(
                        "format", "json"
                )
        ));

        // Connections
        Map<String, WorkflowTemplateDefinition.TemplateConnection> connections = new LinkedHashMap<>();

        connections.put("c1", new WorkflowTemplateDefinition.TemplateConnection(
                "c1", "input", "query-expander", null, null
        ));

        connections.put("c2", new WorkflowTemplateDefinition.TemplateConnection(
                "c2", "query-expander", "vector-search", null, null
        ));

        connections.put("c3", new WorkflowTemplateDefinition.TemplateConnection(
                "c3", "query-expander", "keyword-search",
                "{{hybridSearch}} == true", null
        ));

        connections.put("c4", new WorkflowTemplateDefinition.TemplateConnection(
                "c4", "vector-search", "result-fusion",
                "{{hybridSearch}} == true", null
        ));

        connections.put("c5", new WorkflowTemplateDefinition.TemplateConnection(
                "c5", "keyword-search", "result-fusion", null, null
        ));

        connections.put("c6", new WorkflowTemplateDefinition.TemplateConnection(
                "c6", "vector-search", "reranker",
                "{{hybridSearch}} == false", null
        ));

        connections.put("c7", new WorkflowTemplateDefinition.TemplateConnection(
                "c7", "result-fusion", "reranker",
                "{{rerankResults}} == true", null
        ));

        connections.put("c8", new WorkflowTemplateDefinition.TemplateConnection(
                "c8", "result-fusion", "context-builder",
                "{{rerankResults}} == false", null
        ));

        connections.put("c9", new WorkflowTemplateDefinition.TemplateConnection(
                "c9", "reranker", "context-builder", null, null
        ));

        connections.put("c10", new WorkflowTemplateDefinition.TemplateConnection(
                "c10", "context-builder", "answer-generator", null, null
        ));

        connections.put("c11", new WorkflowTemplateDefinition.TemplateConnection(
                "c11", "answer-generator", "citation-formatter",
                "{{includeCitations}} == true", null
        ));

        connections.put("c12", new WorkflowTemplateDefinition.TemplateConnection(
                "c12", "answer-generator", "output",
                "{{includeCitations}} == false", null
        ));

        connections.put("c13", new WorkflowTemplateDefinition.TemplateConnection(
                "c13", "citation-formatter", "output", null, null
        ));

        WorkflowTemplateDefinition.TemplateStructure structure =
                new WorkflowTemplateDefinition.TemplateStructure("input", nodes, connections);

        return new WorkflowTemplateDefinition(
                ID,
                VERSION,
                getDisplayName(),
                getDescription(),
                getCategory(),
                getTags(),
                Map.of(
                        "vectorStoreId", "",
                        "topK", 5,
                        "minScore", 0.7,
                        "includeCitations", true,
                        "hybridSearch", true,
                        "keywordWeight", 0.3,
                        "rerankResults", true,
                        "maxResponseLength", 1024,
                        "answerStyle", "concise"
                ),
                structure
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Workflow buildWorkflow(String name, Map<String, Object> parameters) {
        String vectorStoreId = getString(parameters, "vectorStoreId", "");
        int topK = getInt(parameters, "topK", 5);
        double minScore = ((Number) parameters.getOrDefault("minScore", 0.7)).doubleValue();
        boolean includeCitations = getBoolean(parameters, "includeCitations", true);
        boolean hybridSearch = getBoolean(parameters, "hybridSearch", true);
        double keywordWeight = ((Number) parameters.getOrDefault("keywordWeight", 0.3)).doubleValue();
        boolean rerankResults = getBoolean(parameters, "rerankResults", true);
        int maxResponseLength = getInt(parameters, "maxResponseLength", 1024);
        String answerStyle = getString(parameters, "answerStyle", "concise");

        Map<String, Object> config = new HashMap<>();
        config.put("name", name);
        config.put("template", ID);
        config.put("version", VERSION);
        config.put("parameters", Map.of(
                "vectorStoreId", vectorStoreId,
                "topK", topK,
                "minScore", minScore,
                "includeCitations", includeCitations,
                "hybridSearch", hybridSearch,
                "keywordWeight", keywordWeight,
                "rerankResults", rerankResults,
                "maxResponseLength", maxResponseLength,
                "answerStyle", answerStyle
        ));

        return createWorkflowFromConfig(name, config);
    }

    private Workflow createWorkflowFromConfig(String name, Map<String, Object> config) {
        return new Workflow() {
            private final String workflowName = name;

            @Override
            public String getId() {
                return workflowName;
            }

            @Override
            public String getName() {
                return workflowName;
            }

            @Override
            public String getDescription() {
                return "Knowledge base workflow created from template";
            }

            @Override
            public Map<String, Object> getMetadata() {
                return Map.of(
                        "template", ID,
                        "templateVersion", VERSION,
                        "createdAt", java.time.Instant.now()
                );
            }
        };
    }
}
