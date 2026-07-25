package br.com.archflow.engine.core;

import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.flow.FlowStatus;
import br.com.archflow.model.flow.StateUpdate;

import java.util.List;


public interface StateManager {
    /**
     * Salva o estado do fluxo
     */
    void saveState(String flowId, FlowState state);
    
    /**
     * Carrega o estado do fluxo
     */
    FlowState loadState(String flowId);
    
    /**
     * Atualiza o estado do fluxo
     */
    void updateState(String flowId, StateUpdate update);

    /**
     * Lista os fluxos que estão em um dado status, entre todos os tenants.
     *
     * <p>Existe para que a camada de API consiga montar filas a partir do estado
     * durável em vez de um índice em memória — em particular a fila de aprovações
     * humanas ({@link FlowStatus#AWAITING_APPROVAL}), que precisa sobreviver a
     * restart e ser encontrável só pelo {@code requestId}.
     *
     * <p>O default devolve lista vazia para não quebrar implementações
     * existentes; toda implementação durável deve sobrescrever.
     *
     * @param status status procurado
     * @return estados no status pedido (nunca {@code null})
     */
    default List<FlowState> findByStatus(FlowStatus status) {
        return List.of();
    }
}