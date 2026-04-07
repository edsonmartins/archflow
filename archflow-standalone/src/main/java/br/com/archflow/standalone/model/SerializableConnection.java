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
    @Override public boolean isErrorPath() { return errorPath; }

    public void setSourceId(String sourceId) { this.sourceId = sourceId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public void setCondition(String condition) { this.condition = condition; }
    public void setErrorPath(boolean errorPath) { this.errorPath = errorPath; }
}
