package br.com.archflow.model.flow;

import br.com.archflow.model.error.ExecutionError;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Data
@Builder
@Getter
@Setter
public class FlowState {
    private String flowId;
    private FlowStatus status;
    private String currentStepId;
    private Map<String, Object> variables;
    private List<ExecutionPath> executionPaths;
    private FlowMetrics metrics;
    private ExecutionError error;
}