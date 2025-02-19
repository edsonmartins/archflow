package br.com.archflow.model.flow;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ExecutionPath {
    private String pathId;
    private PathStatus status;
    private List<String> completedSteps;
    private List<ExecutionPath> parallelBranches;
}