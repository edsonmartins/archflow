package com.example.archflow.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * Demonstrates creating and executing a workflow programmatically via the archflow REST API.
 */
@Component
public class SupportWorkflowRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SupportWorkflowRunner.class);
    private final WebClient client;

    public SupportWorkflowRunner(WebClient archflowWebClient) {
        this.client = archflowWebClient;
    }

    @Override
    public void run(String... args) {
        log.info("--- Archflow Integration Example ---");

        // 1. List available workflows
        log.info("Listing workflows...");
        List<?> workflows = client.get()
                .uri("/workflows")
                .retrieve()
                .bodyToMono(List.class)
                .block();
        log.info("Found {} workflows", workflows != null ? workflows.size() : 0);

        // 2. Execute the first workflow (or a known ID)
        String workflowId = resolveWorkflowId(workflows);
        if (workflowId == null) {
            log.warn("No workflows found. Make sure the archflow backend has workflows configured.");
            return;
        }

        log.info("Executing workflow: {}", workflowId);
        Map<?, ?> execution = client.post()
                .uri("/workflows/{id}/execute", workflowId)
                .bodyValue(Map.of(
                        "channel", "api",
                        "query", "Customer needs help with billing"
                ))
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        if (execution != null) {
            log.info("Execution started: id={}, status={}", execution.get("id"), execution.get("status"));

            // 3. Poll for completion
            String executionId = String.valueOf(execution.get("id"));
            pollExecution(executionId);
        }
    }

    @SuppressWarnings("unchecked")
    private String resolveWorkflowId(List<?> workflows) {
        if (workflows == null || workflows.isEmpty()) return null;
        Map<String, Object> first = (Map<String, Object>) workflows.get(0);
        return String.valueOf(first.get("id"));
    }

    private void pollExecution(String executionId) {
        for (int i = 0; i < 10; i++) {
            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }

            Map<?, ?> status = client.get()
                    .uri("/executions/{id}", executionId)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            String state = status != null ? String.valueOf(status.get("status")) : "UNKNOWN";
            log.info("Execution {} status: {}", executionId, state);

            if ("COMPLETED".equals(state) || "FAILED".equals(state)) {
                log.info("Execution finished with result: {}", status.get("result"));
                return;
            }
        }
        log.warn("Execution {} did not complete within polling window", executionId);
    }
}
