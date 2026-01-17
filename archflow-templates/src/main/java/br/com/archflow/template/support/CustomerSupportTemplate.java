package br.com.archflow.template.support;

import br.com.archflow.model.Workflow;
import br.com.archflow.template.AbstractWorkflowTemplate;
import br.com.archflow.template.WorkflowTemplateDefinition;

import java.util.*;

/**
 * Template for customer support workflows with RAG and multi-agent coordination.
 *
 * <p>This template provides a complete customer support automation solution with:
 * <ul>
 *   <li><b>Intent Classification:</b> Categorize customer inquiries</li>
 *   <li><b>Knowledge Base Search:</b> RAG-based answer retrieval</li>
 *   <li><b>Agent Routing:</b> Route to specialized agents (billing, technical, sales)</li>
 *   <li><b>Response Generation:</b> Context-aware response composition</li>
 *   <li><b>Escalation:</b> Human handoff when needed</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * CustomerSupportTemplate template = new CustomerSupportTemplate();
 *
 * Map<String, Object> params = Map.of(
 *     "knowledgeBaseId", "support-docs",
 *     "escalationEmail", "human-support@company.com",
 *     "maxIterations", 5
 * );
 *
 * Workflow workflow = template.createInstance("my-support", params);
 * }</pre>
 */
public class CustomerSupportTemplate extends AbstractWorkflowTemplate {

    private static final String ID = "customer-support";
    private static final String VERSION = "1.0.0";

    public CustomerSupportTemplate() {
        super(
                ID,
                "Customer Support with RAG",
                "Multi-agent customer support workflow with knowledge base integration and intelligent routing",
                "support",
                Set.of("rag", "support", "multi-agent", "routing"),
                buildParameters()
        );
    }

    private static Map<String, ParameterDefinition> buildParameters() {
        Map<String, ParameterDefinition> params = new LinkedHashMap<>();

        params.put("knowledgeBaseId", ParameterDefinition.required(
                "knowledgeBaseId",
                "ID of the knowledge base/vector store to use for RAG",
                String.class
        ));

        params.put("maxIterations", ParameterDefinition.optional(
                "maxIterations",
                "Maximum number of agent iterations before escalation",
                Integer.class,
                5
        ));

        params.put("escalationEmail", ParameterDefinition.optional(
                "escalationEmail",
                "Email to escalate to for human intervention",
                String.class,
                "support@company.com"
        ));

        params.put("enableSentiment", ParameterDefinition.optional(
                "enableSentiment",
                "Enable sentiment analysis for prioritization",
                Boolean.class,
                true
        ));

        params.put("responseLanguage", ParameterDefinition.enumParameter(
                "responseLanguage",
                "Language for responses",
                "en", "pt", "es", "fr", "de"
        ));

        params.put("confidenceThreshold", ParameterDefinition.optional(
                "confidenceThreshold",
                "Minimum confidence threshold for automated responses (0.0-1.0)",
                Double.class,
                0.8
        ));

        return params;
    }

    @Override
    public WorkflowTemplateDefinition getDefinition() {
        Map<String, WorkflowTemplateDefinition.TemplateNode> nodes = new LinkedHashMap<>();

        // Input node
        nodes.put("input", new WorkflowTemplateDefinition.TemplateNode(
                "input",
                "input",
                "Customer Inquiry Input",
                Map.of(
                        "schema", Map.of(
                                "message", "string",
                                "customerId", "string",
                                "channel", "string"
                        )
                )
        ));

        // Intent classifier node
        nodes.put("intent-classifier", new WorkflowTemplateDefinition.TemplateNode(
                "intent-classifier",
                "llm",
                "Intent Classifier",
                Map.of(
                        "promptTemplate", """
                                Classify the customer inquiry into one of these categories:
                                - billing: Questions about invoices, payments, pricing
                                - technical: Technical issues, bugs, errors
                                - sales: Product inquiries, upgrades, new purchases
                                - general: General questions, information requests

                                Inquiry: {{message}}

                                Respond with JSON: {"intent": "category", "confidence": 0.0-1.0}
                                """,
                        "outputFormat", "json",
                        "temperature", 0.3
                )
        ));

        // Knowledge base search node
        nodes.put("kb-search", new WorkflowTemplateDefinition.TemplateNode(
                "kb-search",
                "vector-search",
                "Knowledge Base Search",
                Map.of(
                        "vectorStoreId", "{{knowledgeBaseId}}",
                        "topK", 5,
                        "minScore", 0.7
                )
        ));

        // Billing agent node
        nodes.put("billing-agent", new WorkflowTemplateDefinition.TemplateNode(
                "billing-agent",
                "llm",
                "Billing Agent",
                Map.of(
                        "promptTemplate", """
                                You are a billing support agent. Answer the customer's question
                                using the following context from our knowledge base:

                                Context:
                                {{kb_results}}

                                Customer Question: {{message}}

                                Provide a helpful, accurate response.
                                """,
                        "outputFormat", "json",
                        "temperature", 0.7
                )
        ));

        // Technical agent node
        nodes.put("technical-agent", new WorkflowTemplateDefinition.TemplateNode(
                "technical-agent",
                "llm",
                "Technical Support Agent",
                Map.of(
                        "promptTemplate", """
                                You are a technical support agent. Help the customer with their issue.

                                System Context: {{system_context}}
                                Knowledge Base: {{kb_results}}

                                Issue Description: {{message}}

                                Provide troubleshooting steps and solutions.
                                """,
                        "outputFormat", "json",
                        "temperature", 0.6
                )
        ));

        // Sales agent node
        nodes.put("sales-agent", new WorkflowTemplateDefinition.TemplateNode(
                "sales-agent",
                "llm",
                "Sales Agent",
                Map.of(
                        "promptTemplate", """
                                You are a sales agent. Help the customer with product inquiries.

                                Customer Question: {{message}}

                                Be helpful but not pushy. Focus on finding the right solution for them.
                                """,
                        "outputFormat", "json",
                        "temperature", 0.8
                )
        ));

        // Response generator node
        nodes.put("response-generator", new WorkflowTemplateDefinition.TemplateNode(
                "response-generator",
                "llm",
                "Response Generator",
                Map.of(
                        "promptTemplate", """
                                Generate a final response to the customer.

                                Agent Response: {{agent_response}}
                                Language: {{responseLanguage}}

                                Ensure the response is:
                                - Clear and concise
                                - Friendly and professional
                                - In the correct language
                                """,
                        "outputFormat", "json"
                )
        ));

        // Sentiment analysis node
        nodes.put("sentiment-analysis", new WorkflowTemplateDefinition.TemplateNode(
                "sentiment-analysis",
                "sentiment",
                "Sentiment Analysis",
                Map.of(
                        "model", "sentiment-analysis",
                        "threshold", 0.3
                )
        ));

        // Escalation node
        nodes.put("escalation", new WorkflowTemplateDefinition.TemplateNode(
                "escalation",
                "human-handoff",
                "Human Escalation",
                Map.of(
                        "email", "{{escalationEmail}}",
                        "includeContext", true
                )
        ));

        // Output node
        nodes.put("output", new WorkflowTemplateDefinition.TemplateNode(
                "output",
                "output",
                "Response Output",
                Map.of(
                        "format", "json"
                )
        ));

        // Connections
        Map<String, WorkflowTemplateDefinition.TemplateConnection> connections = new LinkedHashMap<>();

        connections.put("c1", new WorkflowTemplateDefinition.TemplateConnection(
                "c1", "input", "intent-classifier", null, null
        ));

        connections.put("c2", new WorkflowTemplateDefinition.TemplateConnection(
                "c2", "intent-classifier", "kb-search",
                "{{intent}} != 'general'", null
        ));

        connections.put("c3", new WorkflowTemplateDefinition.TemplateConnection(
                "c3", "kb-search", "billing-agent",
                "{{intent}} == 'billing'", null
        ));

        connections.put("c4", new WorkflowTemplateDefinition.TemplateConnection(
                "c4", "kb-search", "technical-agent",
                "{{intent}} == 'technical'", null
        ));

        connections.put("c5", new WorkflowTemplateDefinition.TemplateConnection(
                "c5", "kb-search", "sales-agent",
                "{{intent}} == 'sales'", null
        ));

        connections.put("c6", new WorkflowTemplateDefinition.TemplateConnection(
                "c6", "billing-agent", "response-generator", null, null
        ));

        connections.put("c7", new WorkflowTemplateDefinition.TemplateConnection(
                "c7", "technical-agent", "response-generator", null, null
        ));

        connections.put("c8", new WorkflowTemplateDefinition.TemplateConnection(
                "c8", "sales-agent", "response-generator", null, null
        ));

        connections.put("c9", new WorkflowTemplateDefinition.TemplateConnection(
                "c9", "response-generator", "sentiment-analysis",
                "{{enableSentiment}} == true", null
        ));

        connections.put("c10", new WorkflowTemplateDefinition.TemplateConnection(
                "c10", "sentiment-analysis", "escalation",
                "{{sentiment}} < 0.3", null
        ));

        connections.put("c11", new WorkflowTemplateDefinition.TemplateConnection(
                "c11", "sentiment-analysis", "output",
                "{{sentiment}} >= 0.3", null
        ));

        connections.put("c12", new WorkflowTemplateDefinition.TemplateConnection(
                "c12", "response-generator", "output",
                "{{enableSentiment}} == false", null
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
                        "knowledgeBaseId", "",
                        "maxIterations", 5,
                        "escalationEmail", "support@company.com",
                        "enableSentiment", true,
                        "responseLanguage", "en",
                        "confidenceThreshold", 0.8
                ),
                structure
        );
    }

    @Override
    protected Workflow buildWorkflow(String name, Map<String, Object> parameters) {
        // This would create an actual Workflow instance
        // For now, we return a placeholder that demonstrates the structure

        String knowledgeBaseId = getString(parameters, "knowledgeBaseId", "");
        int maxIterations = getInt(parameters, "maxIterations", 5);
        String escalationEmail = getString(parameters, "escalationEmail", "support@company.com");
        boolean enableSentiment = getBoolean(parameters, "enableSentiment", true);
        String responseLanguage = getString(parameters, "responseLanguage", "en");
        double confidenceThreshold = ((Number) parameters.getOrDefault("confidenceThreshold", 0.8)).doubleValue();

        // Build the workflow configuration
        Map<String, Object> config = new HashMap<>();
        config.put("name", name);
        config.put("template", ID);
        config.put("version", VERSION);
        config.put("parameters", Map.of(
                "knowledgeBaseId", knowledgeBaseId,
                "maxIterations", maxIterations,
                "escalationEmail", escalationEmail,
                "enableSentiment", enableSentiment,
                "responseLanguage", responseLanguage,
                "confidenceThreshold", confidenceThreshold
        ));

        // Create and return workflow (placeholder implementation)
        return createWorkflowFromConfig(name, config);
    }

    /**
     * Creates a workflow instance from configuration.
     *
     * @param name The workflow name
     * @param config The configuration
     * @return The workflow instance
     */
    private Workflow createWorkflowFromConfig(String name, Map<String, Object> config) {
        // This is a placeholder - in a real implementation,
        // this would use the WorkflowFactory or similar to create
        // a proper Workflow instance with nodes and connections

        // For now, return a simple workflow representation
        return new Workflow() {
            private final String workflowName = name;
            private final Map<String, Object> workflowConfig = config;
            private final String templateId = ID;

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
                return "Customer Support workflow created from template";
            }

            @Override
            public Map<String, Object> getMetadata() {
                return Map.of(
                        "template", templateId,
                        "templateVersion", VERSION,
                        "createdAt", java.time.Instant.now()
                );
            }
        };
    }
}
