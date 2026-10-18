package br.com.archflow.standalone.model;

import br.com.archflow.model.flow.StepConnection;

import java.util.Optional;

/**
 * Serializable implementation of StepConnection.
 */
public class SerializableConnection implements StepConnection {

    private String sourceId;
    private String targetId;
    private String condition;
    /**
     * O ramo declarado na aresta ({@code AUTO}, {@code true}, um caso de switch) quando ela não
     * traz a condição escrita. Transportado cru; quem o converte em condição é
     * {@link SerializableStep#getConnections()}, que conhece o tipo do nó de origem.
     */
    private String branch;
    private boolean errorPath;

    public SerializableConnection() {} // Jackson

    public SerializableConnection(String sourceId, String targetId, String condition, boolean errorPath) {
        this.sourceId = sourceId;
        this.targetId = targetId;
        this.condition = condition;
        this.errorPath = errorPath;
    }

    public static SerializableConnection from(StepConnection conn) {
        return new SerializableConnection(
                conn.getSourceId(), conn.getTargetId(),
                conn.getCondition().orElse(null), conn.isErrorPath());
    }

    @Override public String getSourceId() { return sourceId; }
    @Override public String getTargetId() { return targetId; }
    @Override public Optional<String> getCondition() { return Optional.ofNullable(condition); }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public String getBranch() { return branch; }
    @Override public boolean isErrorPath() { return errorPath; }

    public void setSourceId(String sourceId) { this.sourceId = sourceId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public void setCondition(String condition) { this.condition = condition; }
    public void setBranch(String branch) { this.branch = branch; }

    /** Esta aresta com a condição resolvida a partir do ramo — ou ela mesma, se já tinha condição. */
    SerializableConnection comRamoResolvido(String stepId, String kind) {
        if (condition != null || branch == null) {
            return this;
        }
        String resolvida = br.com.archflow.model.flow.BranchCondition.of(stepId, kind, branch);
        if (resolvida == null) {
            return this;
        }
        SerializableConnection c = new SerializableConnection(sourceId, targetId, resolvida, errorPath);
        c.branch = branch;
        return c;
    }
    public void setErrorPath(boolean errorPath) { this.errorPath = errorPath; }
}
