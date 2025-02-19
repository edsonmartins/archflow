package br.com.archflow.model.flow;

import br.com.archflow.model.enums.StepStatus;

import java.util.HashMap;
import java.util.Map;

/**
 * Classe imutável que representa uma atualização de estado do fluxo
 */
public class StateUpdate {
    private final String stepId;
    private final FlowStatus newStatus;
    private final Map<String, Object> variableUpdates;
    private final StepResult stepResult;
    
    private StateUpdate(Builder builder) {
        this.stepId = builder.stepId;
        this.newStatus = builder.newStatus;
        this.variableUpdates = builder.variableUpdates;
        this.stepResult = builder.stepResult;
    }
    
    /**
     * Aplica a atualização ao estado atual
     */
    public void apply(FlowState state) {
        if (newStatus != null) {
            state.setStatus(newStatus);
        }
        
        if (stepId != null) {
            state.setCurrentStepId(stepId);
        }
        
        if (variableUpdates != null) {
            state.getVariables().putAll(variableUpdates);
        }
        
        if (stepResult != null) {
            updateMetrics(state, stepResult);
        }
    }
    
    private void updateMetrics(FlowState state, StepResult result) {
        FlowMetrics metrics = state.getMetrics();
        if (metrics == null) {
            metrics = new FlowMetrics();
            state.setMetrics(metrics);
        }
        
        // Atualiza métricas do passo
        metrics.getStepMetrics().put(stepId, result.getMetrics());
        
        // Atualiza contadores
        if (result.getStatus() == StepStatus.COMPLETED) {
            metrics.setCompletedSteps(metrics.getCompletedSteps() + 1);
        }
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private String stepId;
        private FlowStatus newStatus;
        private Map<String, Object> variableUpdates = new HashMap<>();
        private StepResult stepResult;
        
        public Builder stepId(String stepId) {
            this.stepId = stepId;
            return this;
        }
        
        public Builder status(FlowStatus status) {
            this.newStatus = status;
            return this;
        }
        
        public Builder variable(String key, Object value) {
            this.variableUpdates.put(key, value);
            return this;
        }
        
        public Builder variables(Map<String, Object> updates) {
            this.variableUpdates.putAll(updates);
            return this;
        }
        
        public Builder stepResult(StepResult result) {
            this.stepResult = result;
            return this;
        }
        
        public StateUpdate build() {
            return new StateUpdate(this);
        }
    }
}