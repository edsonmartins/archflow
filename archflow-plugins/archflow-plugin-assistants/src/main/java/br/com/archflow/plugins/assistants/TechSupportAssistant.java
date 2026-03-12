package br.com.archflow.plugins.assistants;

import br.com.archflow.model.ai.AIAssistant;
import br.com.archflow.model.ai.domain.Action;
import br.com.archflow.model.ai.domain.Analysis;
import br.com.archflow.model.ai.domain.Response;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.plugin.api.spi.ComponentPlugin;

import java.util.*;

/**
 * Reference assistant plugin for technical support.
 *
 * <p>Analyzes user requests to detect common tech support intents
 * (password reset, connectivity, installation, performance) and generates
 * appropriate troubleshooting responses.
 */
public class TechSupportAssistant implements AIAssistant, ComponentPlugin {

    private static final String COMPONENT_ID = "tech-support-assistant";
    private static final String VERSION = "1.0.0";

    private static final Map<String, List<String>> INTENT_KEYWORDS = Map.of(
            "password_reset", List.of("password", "reset", "forgot", "locked", "login", "access"),
            "connectivity", List.of("network", "internet", "wifi", "connection", "offline", "disconnect"),
            "installation", List.of("install", "setup", "configure", "update", "upgrade", "download"),
            "performance", List.of("slow", "lag", "freeze", "crash", "memory", "cpu", "disk")
    );

    private static final Map<String, String> INTENT_RESPONSES = Map.of(
            "password_reset",
            "To reset your password: 1) Go to the login page. 2) Click 'Forgot Password'. " +
            "3) Enter your email. 4) Check your inbox for the reset link. " +
            "If you don't receive the email within 5 minutes, check your spam folder.",

            "connectivity",
            "Let's troubleshoot your connectivity: 1) Restart your router/modem. " +
            "2) Check if other devices can connect. 3) Run a speed test. " +
            "4) Try connecting via ethernet cable. 5) Contact your ISP if the issue persists.",

            "installation",
            "For installation assistance: 1) Verify system requirements. " +
            "2) Download the latest version from the official site. " +
            "3) Run the installer with administrator privileges. " +
            "4) Follow the setup wizard. 5) Restart your system after installation.",

            "performance",
            "To improve performance: 1) Close unnecessary applications. " +
            "2) Check Task Manager for resource usage. 3) Clear temporary files. " +
            "4) Ensure your system is up to date. 5) Consider upgrading RAM or storage."
    );

    private Map<String, Object> config;
    private boolean initialized = false;

    @Override
    public void initialize(Map<String, Object> config) {
        validateConfig(config);
        this.config = config;
        this.initialized = true;
    }

    @Override
    public ComponentMetadata getMetadata() {
        return new ComponentMetadata(
                COMPONENT_ID,
                "Tech Support Assistant",
                "Pattern-based technical support assistant for common IT issues",
                ComponentType.ASSISTANT,
                VERSION,
                Set.of("tech-support", "troubleshooting", "help-desk"),
                List.of(
                        new ComponentMetadata.OperationMetadata(
                                "analyze", "Analyze Request", "Analyze a support request",
                                List.of(new ComponentMetadata.ParameterMetadata("input", "string", "User request", true)),
                                List.of(new ComponentMetadata.ParameterMetadata("analysis", "object", "Request analysis", true))
                        ),
                        new ComponentMetadata.OperationMetadata(
                                "respond", "Generate Response", "Generate a support response",
                                List.of(new ComponentMetadata.ParameterMetadata("analysis", "object", "Analysis result", true)),
                                List.of(new ComponentMetadata.ParameterMetadata("response", "object", "Support response", true))
                        )
                ),
                Map.of(),
                Set.of("support", "reference")
        );
    }

    @Override
    public Object execute(String operation, Object input, ExecutionContext context) throws Exception {
        if (!initialized) {
            throw new IllegalStateException("Assistant not initialized. Call initialize() first.");
        }

        return switch (operation) {
            case "analyze" -> analyzeRequest((String) input, context);
            case "respond" -> generateResponse((Analysis) input, context);
            default -> throw new IllegalArgumentException("Unsupported operation: " + operation);
        };
    }

    @Override
    public Analysis analyzeRequest(String input, ExecutionContext context) {
        if (input == null || input.isBlank()) {
            return new Analysis("unknown", Map.of(), 0.0, List.of("ask_clarification"));
        }

        String lowerInput = input.toLowerCase();
        String bestIntent = "unknown";
        double bestScore = 0.0;
        Map<String, Object> entities = new HashMap<>();

        for (var entry : INTENT_KEYWORDS.entrySet()) {
            long matchCount = entry.getValue().stream()
                    .filter(lowerInput::contains)
                    .count();
            double score = (double) matchCount / entry.getValue().size();

            if (score > bestScore) {
                bestScore = score;
                bestIntent = entry.getKey();
            }
        }

        entities.put("detected_intent", bestIntent);
        entities.put("input_length", input.length());

        List<String> suggestedActions = new ArrayList<>();
        if (bestScore >= 0.3) {
            suggestedActions.add("provide_solution");
        }
        if (bestScore < 0.5) {
            suggestedActions.add("ask_details");
        }

        return new Analysis(bestIntent, entities, bestScore, suggestedActions);
    }

    @Override
    public Response generateResponse(Analysis analysis, ExecutionContext context) {
        String intent = analysis.intent();
        String content = INTENT_RESPONSES.getOrDefault(intent,
                "I'd be happy to help. Could you please provide more details about your issue? " +
                "Describe what you're experiencing, any error messages, and what you've already tried.");

        List<Action> actions = new ArrayList<>();
        if (analysis.confidence() < 0.3) {
            actions.add(Action.of("escalate", "Escalate to human agent"));
        }
        if (analysis.suggestedActions().contains("ask_details")) {
            actions.add(Action.of("followup", "Ask for more details"));
        }

        Map<String, Object> metadata = Map.of(
                "intent", intent,
                "confidence", analysis.confidence(),
                "source", COMPONENT_ID
        );

        return new Response(content, metadata, actions);
    }

    @Override
    public String getSpecialization() {
        return "Technical Support";
    }

    @Override
    public void validateConfig(Map<String, Object> config) {
        // No required configuration
    }

    @Override
    public void shutdown() {
        this.config = null;
        this.initialized = false;
    }
}
