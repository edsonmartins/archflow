package br.com.archflow.example.controller;

import br.com.archflow.agent.Agent;
import br.com.archflow.agent.AgentExecutor;
import br.com.archflow.agent.AgentResponse;
import br.com.archflow.core.FlowEngine;
import br.com.archflow.core.ExecutionResult;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Controller REST para o exemplo de atendimento ao cliente.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class CustomerSupportController {

    private final FlowEngine flowEngine;
    private final Agent customerServiceAgent;
    private final AgentExecutor agentExecutor;

    public CustomerSupportController(
            FlowEngine flowEngine,
            Agent customerServiceAgent,
            AgentExecutor agentExecutor) {
        this.flowEngine = flowEngine;
        this.customerServiceAgent = customerServiceAgent;
        this.agentExecutor = agentExecutor;
    }

    /**
     * Executa o workflow de atendimento ao cliente.
     */
    @PostMapping("/workflows/customer-support/execute")
    public ResponseEntity<Map<String, Object>> executeWorkflow(
            @RequestBody Map<String, String> request) {

        String message = request.get("message");

        ExecutionResult result = flowEngine.execute(
                "customer-support",
                Map.of("message", message)
        );

        return ResponseEntity.ok(Map.of(
                "status", result.getStatus().name(),
                "output", result.getOutput(),
                "duration", result.getDuration().toMillis() + "ms"
        ));
    }

    /**
     * Chat com o agente de atendimento (resposta completa).
     */
    @PostMapping("/agents/customer-service/chat")
    public ResponseEntity<Map<String, Object>> chatWithAgent(
            @RequestBody Map<String, String> request) {

        String message = request.get("message");

        AgentResponse response = agentExecutor.execute(
                customerServiceAgent,
                message
        );

        return ResponseEntity.ok(Map.of(
                "response", response.getText(),
                "tokens", response.getTokenUsage() != null ?
                        response.getTokenUsage().total() : 0
        ));
    }

    /**
     * Chat com o agente de atendimento (streaming).
     */
    @GetMapping(value = "/agents/customer-service/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestParam String message) {

        SseEmitter emitter = new SseEmitter(30000L);

        CompletableFuture.runAsync(() -> {
            try {
                String response = agentExecutor.execute(
                        customerServiceAgent,
                        message
                ).getText();

                // Simula streaming enviando chunks
                String[] words = response.split(" ");
                for (String word : words) {
                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data(word + " "));
                    Thread.sleep(50);
                }

                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * Health check.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "archflow-spring-boot-example"
        ));
    }
}
