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
        private final String branch;
        private final Map<String, Object> configuration;

        public TemplateConnection(
                String id,
                String source,
                String target,
                String condition,
                Map<String, Object> configuration) {
            this(id, source, target, condition, null, configuration);
        }

        private TemplateConnection(
                String id,
                String source,
                String target,
                String condition,
                String branch,
                Map<String, Object> configuration) {
            this.id = id;
            this.source = source;
            this.target = target;
            this.condition = condition;
            this.branch = branch;
            this.configuration = configuration != null ? Map.copyOf(configuration) : Map.of();
        }

        /**
         * Aresta que segue um ramo do nó de origem ({@code true}/{@code false} de um {@code condition}, um
         * caso de um {@code switch}, {@code AUTO}/{@code REVIEW}/{@code ESCALATE} de um {@code decision}).
         *
         * <p>RCHFLW-203: os templates ramificavam por condições sobre variáveis que nenhum passo grava
         * ({@code {{intent}} == 'billing'}). O documento canônico já tem o {@code branch} da aresta, que a
         * factory converte pela regra única do {@code BranchCondition} (archflow-model) — faltava o template
         * conseguir declará-lo.</p>
         */
        public static TemplateConnection branch(String id, String source, String target, String branch) {
            return new TemplateConnection(id, source, target, null, branch, null);
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

        /** O ramo do nó de origem que a aresta segue, ou {@code null} (ver {@link #branch}). */
        public String getBranch() {
            return branch;
        }

        public Map<String, Object> getConfiguration() {
            return configuration;
        }
    }
}
