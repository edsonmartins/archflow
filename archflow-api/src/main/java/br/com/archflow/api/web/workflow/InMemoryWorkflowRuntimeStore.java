package br.com.archflow.api.web.workflow;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryWorkflowRuntimeStore {

    private final Map<String, Map<String, Object>> workflows = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> executions = new ConcurrentHashMap<>();

    public InMemoryWorkflowRuntimeStore() {
        seedDemoWorkflow();
    }

    public Collection<Map<String, Object>> workflows() {
        return workflows.values();
    }

    public Map<String, Object> getWorkflow(String id) {
        return workflows.get(id);
    }

    public Map<String, Object> putWorkflow(String id, Map<String, Object> workflow) {
        workflows.put(id, workflow);
        return workflow;
    }

    public boolean hasWorkflow(String id) {
        return workflows.containsKey(id);
    }

    public void deleteWorkflow(String id) {
        workflows.remove(id);
        executions.entrySet().removeIf(entry -> id.equals(entry.getValue().get("workflowId")));
    }

    public List<Map<String, Object>> executions() {
        return executions.values().stream()
                .sorted(Comparator.comparing((Map<String, Object> exec) ->
                        exec.getOrDefault("startedAt", "").toString()).reversed())
                .toList();
    }

    public Map<String, Object> getExecution(String id) {
        return executions.get(id);
    }

    public Map<String, Object> putExecution(String id, Map<String, Object> execution) {
        executions.put(id, execution);
        return execution;
    }

    public Map<String, Object> createExecution(String workflowId, String workflowName) {
        var executionId = "exec-" + UUID.randomUUID().toString().substring(0, 8);
        var startedAt = Instant.now().toString();
        var execution = new LinkedHashMap<String, Object>();
        execution.put("id", executionId);
        execution.put("workflowId", workflowId);
        execution.put("workflowName", workflowName);
        execution.put("status", "RUNNING");
        execution.put("startedAt", startedAt);
        execution.put("completedAt", null);
        execution.put("duration", null);
        execution.put("error", null);
        executions.put(executionId, execution);
        return execution;
    }

    private void seedDemoWorkflow() {
        var demo = new HashMap<String, Object>();
        demo.put("id", "wf-demo-001");
        demo.put("status", "active");
        demo.put("updatedAt", Instant.now().toString());
        demo.put("metadata", Map.of(
                "name", "Customer Support Agent",
                "description", "AI-powered customer support workflow with escalation",
                "version", "1.0.0",
                "category", "support",
                "tags", List.of("ai", "support", "agent")
        ));
        demo.put("steps", List.of(
                Map.of("id", "step-1", "type", "AGENT", "name", "Classify Intent"),
                Map.of("id", "step-2", "type", "TOOL", "name", "Search Knowledge Base"),
                Map.of("id", "step-3", "type", "AGENT", "name", "Generate Response")
        ));
        demo.put("configuration", Map.of());
        workflows.put("wf-demo-001", demo);

        var seededExecution = new LinkedHashMap<>(createExecution("wf-demo-001", "Customer Support Agent"));
        executions.values().removeIf(execution -> "wf-demo-001".equals(execution.get("workflowId")));
        seededExecution.put("id", "exec-demo-001");
        seededExecution.put("status", "COMPLETED");
        seededExecution.put("completedAt", Instant.now().toString());
        seededExecution.put("duration", 1840);
        executions.put("exec-demo-001", seededExecution);
    }
}
