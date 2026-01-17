/**
 * Workflow templates for common AI patterns.
 *
 * <h2>Overview</h2>
 * <p>Provides pre-built workflow templates that can be instantiated and customized
 * for common AI use cases.</p>
 *
 * <h2>Available Templates</h2>
 * <ul>
 *   <li>{@link br.com.archflow.template.support.CustomerSupportTemplate} - Customer support with RAG</li>
 *   <li>{@link br.com.archflow.template.processing.DocumentProcessingTemplate} - Document processing</li>
 *   <li>{@link br.com.archflow.template.knowledge.KnowledgeBaseTemplate} - Knowledge base Q&A</li>
 *   <li>{@link br.com.archflow.template.supervisor.AgentSupervisorTemplate} - Multi-agent supervisor</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Get the registry
 * WorkflowTemplateRegistry registry = WorkflowTemplateRegistry.getInstance();
 *
 * // Get a template
 * WorkflowTemplate template = registry.getTemplate("customer-support").orElseThrow();
 *
 * // Create a workflow instance
 * Map<String, Object> params = Map.of(
 *     "knowledgeBaseId", "my-kb",
 *     "escalationEmail", "support@company.com"
 * );
 *
 * Workflow workflow = template.createInstance("my-support", params);
 * }</pre>
 *
 * @since 1.0.0
 */
package br.com.archflow.template;
