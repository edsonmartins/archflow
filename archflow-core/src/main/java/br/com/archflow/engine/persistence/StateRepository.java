package br.com.archflow.engine.persistence;


import br.com.archflow.model.flow.AuditLog;
import br.com.archflow.model.flow.FlowState;

public interface StateRepository {
    void saveState(String flowId, FlowState state);
    FlowState getState(String flowId);
    void saveAuditLog(String flowId, AuditLog log);
}