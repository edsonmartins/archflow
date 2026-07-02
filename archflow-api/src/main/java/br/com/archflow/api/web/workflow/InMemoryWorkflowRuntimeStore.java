package br.com.archflow.api.web.workflow;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryWorkflowRuntimeStore {

    private final Map<String, Map<String, Object>> workflows = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> executions = new ConcurrentHashMap<>();
    // Per-execution stepId → step-record index so lifecycle events patch in
    // O(1) instead of rescanning the growing steps list on every event.
    private final Map<String, Map<String, Map<String, Object>>> stepIndexes = new ConcurrentHashMap<>();

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
        executions.entrySet().removeIf(entry -> {
            if (id.equals(entry.getValue().get("workflowId"))) {
                stepIndexes.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }

    public List<Map<String, Object>> executions() {
        return executions.values().stream()
                .map(InMemoryWorkflowRuntimeStore::snapshot)
                .sorted(Comparator.comparing((Map<String, Object> exec) ->
                        exec.getOrDefault("startedAt", "").toString()).reversed())
                .toList();
    }

    public Map<String, Object> getExecution(String id) {
        var execution = executions.get(id);
        return execution == null ? null : snapshot(execution);
    }

    /**
     * Copies an execution record under its monitor so readers (Jackson
     * serialization in the controllers) never iterate a map/steps list
     * that an engine lifecycle callback is mutating concurrently.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> snapshot(Map<String, Object> execution) {
        synchronized (execution) {
            var copy = new LinkedHashMap<>(execution);
            if (copy.get("steps") instanceof List<?> steps) {
                var stepsCopy = new ArrayList<Map<String, Object>>(steps.size());
                for (Object step : steps) {
                    stepsCopy.add(new LinkedHashMap<>((Map<String, Object>) step));
                }
                copy.put("steps", stepsCopy);
            }
            return copy;
        }
    }

    /** Marks a resumed execution RUNNING again (locked counterpart of completeExecution). */
    public void markResumed(String id) {
        var execution = executions.get(id);
        if (execution == null) {
            return;
        }
        synchronized (execution) {
            execution.put("status", "RUNNING");
            execution.put("completedAt", null);
            execution.put("error", null);
        }
    }

    public Map<String, Object> putExecution(String id, Map<String, Object> execution) {
        executions.put(id, execution);
        return execution;
    }

    /** Marks a running execution terminal (status + completedAt + optional error). */
    public void completeExecution(String id, String status, String error) {
        var execution = executions.get(id);
        if (execution == null) {
            return;
        }
        synchronized (execution) {
            execution.put("status", status);
            execution.put("completedAt", Instant.now().toString());
            execution.put("error", error);
        }
    }

    /** Records the wall-clock duration of an execution (set by the lifecycle listener). */
    public void recordDuration(String id, long durationMs) {
        var execution = executions.get(id);
        if (execution == null) {
            return;
        }
        synchronized (execution) {
            execution.put("duration", durationMs);
        }
    }

    /**
     * Appends/updates a per-step record inside the execution's {@code steps} list,
     * keyed by stepId. Called from engine lifecycle callbacks (virtual threads),
     * hence the per-execution synchronization.
     */
    @SuppressWarnings("unchecked")
    public void recordStep(String executionId, String stepId, Map<String, Object> patch) {
        var execution = executions.get(executionId);
        if (execution == null || stepId == null) {
            return;
        }
        synchronized (execution) {
            var steps = (List<Map<String, Object>>) execution.computeIfAbsent(
                    "steps", k -> new ArrayList<Map<String, Object>>());
            var index = stepIndexes.computeIfAbsent(executionId, k -> new LinkedHashMap<>());
            var step = index.computeIfAbsent(stepId, k -> {
                var created = new LinkedHashMap<String, Object>();
                created.put("stepId", stepId);
                steps.add(created);
                return created;
            });
            step.putAll(patch);
        }
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
