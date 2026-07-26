package br.com.archflow.engine.persistence;

import br.com.archflow.model.flow.AuditLog;
import br.com.archflow.model.flow.FlowState;

import java.util.List;

public interface StateRepository {
    void saveState(String flowId, FlowState state);
    FlowState getState(String flowId);
    void saveAuditLog(String flowId, AuditLog log);

    /**
     * Salva estado com isolamento por tenant.
     */
    default void saveState(String tenantId, String flowId, FlowState state) {
        saveState(flowId, state);
    }

    /**
     * Recupera estado com isolamento por tenant.
     */
    default FlowState getState(String tenantId, String flowId) {
        return getState(flowId);
    }

    /**
     * Salva audit log com isolamento por tenant.
     */
    default void saveAuditLog(String tenantId, String flowId, AuditLog log) {
        saveAuditLog(flowId, log);
    }

    /**
     * Lista todos os estados de flows de um tenant.
     */
    default List<FlowState> getStatesByTenant(String tenantId) {
        return List.of();
    }

    /**
     * Lista os estados em um dado status, entre todos os tenants. Suporta as
     * filas montadas a partir do estado durável (ex.: aprovações pendentes),
     * onde a busca começa por um id de requisição e não por tenant.
     *
     * @param status nome do status ({@code FlowStatus.name()})
     */
    default List<FlowState> getStatesByStatus(String status) {
        return List.of();
    }
}