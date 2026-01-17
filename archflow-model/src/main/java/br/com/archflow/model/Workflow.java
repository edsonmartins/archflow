package br.com.archflow.model;

import br.com.archflow.model.config.FlowConfiguration;
import br.com.archflow.model.flow.FlowMetadata;

import java.util.List;
import java.util.Map;

/**
 * Interface representing a workflow in the archflow system.
 *
 * <p>A workflow is a composition of AI components (agents, chains, tools)
 * that work together to accomplish a specific task.
 *
 * <p>This interface provides an abstraction over the underlying {@link br.com.archflow.model.flow.Flow}
 * to allow template-based workflow creation.
 *
 * @since 1.0.0
 */
public interface Workflow {

    /**
     * Gets the unique identifier of this workflow.
     *
     * @return The workflow ID
     */
    String getId();

    /**
     * Gets the name of this workflow.
     *
     * @return The workflow name
     */
    String getName();

    /**
     * Gets the description of this workflow.
     *
     * @return The workflow description
     */
    String getDescription();

    /**
     * Gets the metadata associated with this workflow.
     *
     * @return The workflow metadata
     */
    Map<String, Object> getMetadata();

    /**
     * Creates a builder for constructing Workflow instances.
     *
     * @return A new WorkflowBuilder
     */
    static WorkflowBuilder builder() {
        return new WorkflowBuilder();
    }

    /**
     * Builder for creating Workflow instances.
     */
    class WorkflowBuilder {
        private String id;
        private String name;
        private String description;
        private Map<String, Object> metadata;

        public WorkflowBuilder id(String id) {
            this.id = id;
            return this;
        }

        public WorkflowBuilder name(String name) {
            this.name = name;
            return this;
        }

        public WorkflowBuilder description(String description) {
            this.description = description;
            return this;
        }

        public WorkflowBuilder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public WorkflowBuilder addMetadata(String key, Object value) {
            if (this.metadata == null) {
                this.metadata = new java.util.HashMap<>();
            }
            this.metadata.put(key, value);
            return this;
        }

        /**
         * Builds the Workflow instance.
         *
         * @return A new Workflow instance
         */
        public Workflow build() {
            return new Workflow() {
                private final String workflowId = id;
                private final String workflowName = name != null ? name : id;
                private final String workflowDescription = description != null ? description : "";
                private final Map<String, Object> workflowMetadata = metadata != null ? Map.copyOf(metadata) : Map.of();

                @Override
                public String getId() {
                    return workflowId;
                }

                @Override
                public String getName() {
                    return workflowName;
                }

                @Override
                public String getDescription() {
                    return workflowDescription;
                }

                @Override
                public Map<String, Object> getMetadata() {
                    return workflowMetadata;
                }
            };
        }
    }
}
