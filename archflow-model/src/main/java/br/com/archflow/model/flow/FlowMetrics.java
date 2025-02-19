package br.com.archflow.model.flow;

import br.com.archflow.model.metrics.StepMetrics;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
public class FlowMetrics {
    private long startTime;
    private long endTime;
    private Map<String, StepMetrics> stepMetrics;
    private int totalSteps;
    private int completedSteps;

    public FlowMetrics() {
    }

    @Builder
    public FlowMetrics(long startTime, long endTime, Map<String, StepMetrics> stepMetrics, int totalSteps, int completedSteps) {
        this.startTime = startTime;
        this.endTime = endTime;
        this.stepMetrics = stepMetrics;
        this.totalSteps = totalSteps;
        this.completedSteps = completedSteps;
    }
}