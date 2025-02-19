package br.com.archflow.model.flow;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class AuditLog {
    private String flowId;
    private Instant timestamp;
    private FlowState state;
    private String stepId;
    private StepResult stepResult;
}