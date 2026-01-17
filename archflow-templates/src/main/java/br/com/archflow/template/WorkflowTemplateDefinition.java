package br.com.archflow.template;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Definition of a workflow template.
 *
 * <p>This class contains the metadata and structure of a workflow template,
 * including its nodes, connections, and default configuration.
 */
public class WorkflowTemplateDefinition {

    private final String id;
    private final String version;
    private final String name;
    private final String description;
    private final String category;
    private final Set<String> tags;
    private final Instant createdAt;
    private final Map<String, Object> defaultParameters;
    private final TemplateStructure structure;

    public WorkflowTemplateDefinition(
            String id,
            String version,
            String name,
            String description,
            String category,
            Set<String> tags,
            Map<String, Object> defaultParameters,
            TemplateStructure structure) {
        this.id = id;
        this.version = version;
        this.name = name;
        this.description = description;
        this.category = category;
        this.tags = tags != null ? Set.copyOf(tags) : Set.of();
        this.createdAt = Instant.now();
        this.defaultParameters = Map.copyOf(defaultParameters);
        this.structure = structure;
    }

    public String getId() {
        return id;
    }

    public String getVersion() {
        return version;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getCategory() {
        return category;
    }

    public Set<String> getTags() {
        return tags;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Map<String, Object> getDefaultParameters() {
        return defaultParameters;
    }

    public TemplateStructure getStructure() {
        return structure;
    }

    /**
     * The structural definition of a workflow template.
     */
    public static class TemplateStructure {
        private final String entryPoint;
        private final Map<String, TemplateNode> nodes;
        private final Map<String, TemplateConnection> connections;

        public TemplateStructure(
                String entryPoint,
                Map<String, TemplateNode> nodes,
                Map<String, TemplateConnection> connections) {
            this.entryPoint = entryPoint;
            this.nodes = Map.copyOf(nodes);
            this.connections = Map.copyOf(connections);
        }

        public String getEntryPoint() {
            return entryPoint;
        }

        public Map<String, TemplateNode> getNodes() {
            return nodes;
        }

        public Map<String, TemplateConnection> getConnections() {
            return connections;
        }
    }

    /**
     * A node in the workflow template.
     */
    public static class TemplateNode {
        private final String id;
        private final String type;
        private final String name;
        private final Map<String, Object> configuration;

        public TemplateNode(
                String id,
                String type,
                String name,
                Map<String, Object> configuration) {
            this.id = id;
            this.type = type;
            this.name = name;
            this.configuration = configuration != null ? Map.copyOf(configuration) : Map.of();
        }

        public String getId() {
            return id;
        }

        public String getType() {
            return type;
        }

        public String getName() {
            return name;
        }

        public Map<String, Object> getConfiguration() {
            return configuration;
        }
    }

    /**
     * A connection between nodes in the workflow template.
     */
    public static class TemplateConnection {
        private final String id;
        private final String source;
        private final String target;
        private final String condition;
        private final Map<String, Object> configuration;

        public TemplateConnection(
                String id,
                String source,
                String target,
                String condition,
                Map<String, Object> configuration) {
            this.id = id;
            this.source = source;
            this.target = target;
            this.condition = condition;
            this.configuration = configuration != null ? Map.copyOf(configuration) : Map.of();
        }

        public String getId() {
            return id;
        }

        public String getSource() {
            return source;
        }

        public String getTarget() {
            return target;
        }

        public String getCondition() {
            return condition;
        }

        public Map<String, Object> getConfiguration() {
            return configuration;
        }
    }
}
