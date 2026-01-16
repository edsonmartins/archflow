package br.com.archflow.agent.tool;

import java.util.Objects;
import java.util.UUID;

/**
 * Identificador único de execução com suporte a hierarquia parent-child.
 *
 * <p>Este ID permite rastrear a execução completa de um workflow,
 * incluindo chamadas aninhadas de tools e sub-workflows.
 *
 * <p>Estrutura do ID: {@code prefix_rootId_counter}
 * <ul>
 *   <li>prefix: Tipo de execução (FLOW, AGENT, TOOL)</li>
 *   <li>rootId: ID único da execução raiz</li>
 *   <li>counter: Sequencial para este nível</li>
 * </ul>
 *
 * <p>Exemplos:
 * <pre>
 * FLOW_abc123_000           # Execução raiz de flow
 * AGENT_abc123_001          # Agent executado pelo flow
 * TOOL_abc123_002           # Tool chamada pelo agent
 * TOOL_abc123_002_001       # Tool aninhada (outra tool chamada pela primeira)
 * </pre>
 */
public class ExecutionId {

    private final String id;
    private final ExecutionType type;
    private final String rootId;
    private final String parentId;
    private final int sequence;
    private final int depth;

    private ExecutionId(Builder builder) {
        this.type = builder.type;
        this.rootId = builder.rootId != null ? builder.rootId : UUID.randomUUID().toString();
        this.parentId = builder.parentId;
        this.sequence = builder.sequence;
        this.depth = builder.depth;
        this.id = buildId();
    }

    private String buildId() {
        StringBuilder sb = new StringBuilder();
        sb.append(type.prefix()).append("_");
        sb.append(rootId);

        if (parentId != null && !parentId.isEmpty()) {
            sb.append("_").append(parentId.substring(parentId.lastIndexOf('_') + 1));
        }

        sb.append("_").append(String.format("%03d", sequence));
        return sb.toString();
    }

    /**
     * Cria um novo ExecutionId raiz.
     *
     * @param type Tipo de execução
     * @return Novo ExecutionId raiz
     */
    public static ExecutionId createRoot(ExecutionType type) {
        return new Builder()
                .type(type)
                .sequence(0)
                .depth(0)
                .build();
    }

    /**
     * Cria um ExecutionId filho deste.
     *
     * @param childType Tipo da execução filha
     * @return Novo ExecutionId filho
     */
    public ExecutionId createChild(ExecutionType childType) {
        return new Builder()
                .type(childType)
                .rootId(this.rootId)
                .parentId(this.id)
                .sequence(0) // Será incrementado pelo ExecutionTracker
                .depth(this.depth + 1)
                .build();
    }

    /**
     * Cria um ExecutionId irmão (mesmo pai).
     *
     * @param siblingType Tipo do irmão
     * @param sequence    Sequência do irmão
     * @return Novo ExecutionId irmão
     */
    public ExecutionId createSibling(ExecutionType siblingType, int sequence) {
        return new Builder()
                .type(siblingType)
                .rootId(this.rootId)
                .parentId(this.parentId)
                .sequence(sequence)
                .depth(this.depth)
                .build();
    }

    public String getId() {
        return id;
    }

    public ExecutionType getType() {
        return type;
    }

    public String getRootId() {
        return rootId;
    }

    public String getParentId() {
        return parentId;
    }

    public int getSequence() {
        return sequence;
    }

    public int getDepth() {
        return depth;
    }

    /**
     * Verifica se este é um ID raiz (sem pai).
     *
     * @return true se raiz
     */
    public boolean isRoot() {
        return parentId == null || parentId.isEmpty();
    }

    /**
     * Retorna o caminho completo até a raiz.
     *
     * @return Caminho de IDs do pai até este
     */
    public String getPath() {
        if (isRoot()) {
            return id;
        }
        return parentId + " -> " + id;
    }

    /**
     * Retorna uma representação curta do ID (últimos 8 caracteres).
     *
     * @return ID curto
     */
    public String getShortId() {
        return id.substring(Math.max(0, id.length() - 8));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ExecutionId that = (ExecutionId) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return id;
    }

    public static ExecutionId fromString(String id) {
        String[] parts = id.split("_");
        if (parts.length < 3) {
            throw new IllegalArgumentException("Invalid ExecutionId format: " + id);
        }
        ExecutionType type = ExecutionType.fromPrefix(parts[0]);
        String rootId = parts[1];

        Builder builder = new Builder()
                .type(type)
                .rootId(rootId);

        if (parts.length > 3) {
            builder.parentId(String.join("_", java.util.Arrays.copyOfRange(parts, 0, parts.length - 1)));
        }

        try {
            builder.sequence(Integer.parseInt(parts[parts.length - 1]));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid sequence in ExecutionId: " + id, e);
        }

        return builder.build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private ExecutionType type;
        private String rootId;
        private String parentId;
        private int sequence;
        private int depth;

        public Builder type(ExecutionType type) {
            this.type = type;
            return this;
        }

        public Builder rootId(String rootId) {
            this.rootId = rootId;
            return this;
        }

        public Builder parentId(String parentId) {
            this.parentId = parentId;
            return this;
        }

        public Builder sequence(int sequence) {
            this.sequence = sequence;
            return this;
        }

        public Builder depth(int depth) {
            this.depth = depth;
            return this;
        }

        public ExecutionId build() {
            if (type == null) {
                throw new IllegalArgumentException("type is required");
            }
            return new ExecutionId(this);
        }
    }

    /**
     * Tipo de execução.
     */
    public enum ExecutionType {
        FLOW("FLOW"),
        AGENT("AGENT"),
        TOOL("TOOL"),
        CHAIN("CHAIN");

        private final String prefix;

        ExecutionType(String prefix) {
            this.prefix = prefix;
        }

        public String prefix() {
            return prefix;
        }

        public static ExecutionType fromPrefix(String prefix) {
            for (ExecutionType type : values()) {
                if (type.prefix.equals(prefix)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown prefix: " + prefix);
        }
    }
}
