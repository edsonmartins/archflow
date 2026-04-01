package br.com.archflow.plugins.agents;

import br.com.archflow.model.ai.AIAgent;
import br.com.archflow.model.ai.domain.*;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.plugin.api.spi.ComponentPlugin;

import java.util.*;

/**
 * Agent specialized for customer service and sales conversations.
 *
 * <p>Classifies user intents, routes queries to specialized handlers,
 * tracks conversation context, and escalates to human agents when confidence
 * drops below a configurable threshold.
 */
public class ConversationalAgent implements AIAgent, ComponentPlugin {

    private static final String COMPONENT_ID = "conversational-agent";
    private static final String VERSION = "1.0.0";

    private static final double DEFAULT_ESCALATION_THRESHOLD = 0.5;
    private static final int DEFAULT_MAX_CONTEXT_MESSAGES = 10;

    private Map<String, Object> config;
    private boolean initialized = false;

    private double escalationThreshold;
    private int maxContextMessages;
    private Map<String, String> intentDescriptions;
    private IntentClassifier intentClassifier;
    private EscalationPolicy escalationPolicy;
    private final Deque<String> conversationContext = new java.util.concurrent.ConcurrentLinkedDeque<>();

    /**
     * Functional interface for classifying user intent from a request.
     */
    @FunctionalInterface
    public interface IntentClassifier {
        /**
         * Classifies the intent of a user request.
         *
         * @param request the user request text
         * @param intentDescriptions available intents and their descriptions
         * @return an Analysis containing the detected intent, entities, and confidence
         */
        Analysis classify(String request, Map<String, String> intentDescriptions);
    }

    /**
     * Functional interface for deciding when to escalate to a human agent.
     */
    @FunctionalInterface
    public interface EscalationPolicy {
        /**
         * Determines whether the conversation should be escalated to a human.
         *
         * @param analysis the analysis of the current request
         * @param conversationHistory recent conversation messages
         * @return true if escalation is recommended
         */
        boolean shouldEscalate(Analysis analysis, List<String> conversationHistory);
    }

    /**
     * Creates a ConversationalAgent with default settings.
     */
    public ConversationalAgent() {
        this.escalationThreshold = DEFAULT_ESCALATION_THRESHOLD;
        this.maxContextMessages = DEFAULT_MAX_CONTEXT_MESSAGES;
        this.intentDescriptions = new LinkedHashMap<>();
        this.intentClassifier = ConversationalAgent::defaultClassify;
        this.escalationPolicy = (analysis, history) -> analysis.confidence() < escalationThreshold;
    }

    @Override
    public void initialize(Map<String, Object> config) {
        validateConfig(config);
        this.config = config;

        if (config.containsKey("escalationThreshold")) {
            this.escalationThreshold = ((Number) config.get("escalationThreshold")).doubleValue();
        }
        if (config.containsKey("maxContextMessages")) {
            this.maxContextMessages = ((Number) config.get("maxContextMessages")).intValue();
        }
        if (config.containsKey("intents")) {
            @SuppressWarnings("unchecked")
            Map<String, String> intents = (Map<String, String>) config.get("intents");
            this.intentDescriptions = new LinkedHashMap<>(intents);
        }

        this.initialized = true;
    }

    @Override
    public ComponentMetadata getMetadata() {
        return new ComponentMetadata(
                COMPONENT_ID,
                "Conversational Agent",
                "Customer service and sales conversational agent with intent classification and escalation",
                ComponentType.AGENT,
                VERSION,
                Set.of("conversation", "customer-service", "sales", "intent-classification"),
                List.of(
                        new ComponentMetadata.OperationMetadata(
                                "analyzeRequest", "Analyze Request",
                                "Analyze a user request to detect intent, entities, and confidence",
                                List.of(new ComponentMetadata.ParameterMetadata(
                                        "request", "string", "User request text", true)),
                                List.of(new ComponentMetadata.ParameterMetadata(
                                        "analysis", "object", "Request analysis", true))
                        ),
                        new ComponentMetadata.OperationMetadata(
                                "generateResponse", "Generate Response",
                                "Generate a response routed by detected intent",
                                List.of(new ComponentMetadata.ParameterMetadata(
                                        "request", "string", "User request text", true)),
                                List.of(new ComponentMetadata.ParameterMetadata(
                                        "response", "object", "Generated response", true))
                        ),
                        new ComponentMetadata.OperationMetadata(
                                "executeTask", "Execute Task", "Execute a conversational task",
                                List.of(new ComponentMetadata.ParameterMetadata(
                                        "task", "object", "Task to execute", true)),
                                List.of(new ComponentMetadata.ParameterMetadata(
                                        "result", "object", "Task result", true))
                        )
                ),
                Map.of(
                        "escalationThreshold", String.valueOf(DEFAULT_ESCALATION_THRESHOLD),
                        "maxContextMessages", String.valueOf(DEFAULT_MAX_CONTEXT_MESSAGES)
                ),
                Set.of("agent", "conversational", "customer-service")
        );
    }

    @Override
    public Object execute(String operation, Object input, ExecutionContext context) throws Exception {
        if (!initialized) {
            throw new IllegalStateException("Agent not initialized. Call initialize() first.");
        }

        return switch (operation) {
            case "analyzeRequest" -> analyzeRequest((String) input);
            case "generateResponse" -> generateResponse((String) input);
            case "executeTask" -> executeTask((Task) input, context);
            case "makeDecision" -> makeDecision(context);
            case "planActions" -> planActions((Goal) input, context);
            default -> throw new IllegalArgumentException("Unsupported operation: " + operation);
        };
    }

    /**
     * Analyzes a user request to detect intent, extract entities, and compute confidence.
     *
     * @param request the user request text
     * @return an Analysis with detected intent, entities, and confidence
     */
    public Analysis analyzeRequest(String request) {
        if (request == null || request.isBlank()) {
            return new Analysis("unknown", Map.of(), 0.0, List.of("escalate"));
        }

        addToContext(request);
        return intentClassifier.classify(request, intentDescriptions);
    }

    /**
     * Generates a response routed by the detected intent.
     *
     * <p>If confidence is below the escalation threshold or the escalation policy
     * triggers, returns an escalation response instead.
     *
     * @param request the user request text
     * @return a Response with content and any follow-up actions
     */
    public Response generateResponse(String request) {
        Analysis analysis = analyzeRequest(request);

        List<String> history = new ArrayList<>(conversationContext);
        if (escalationPolicy.shouldEscalate(analysis, history)) {
            return new Response(
                    "I'd like to connect you with a human agent who can better assist you.",
                    Map.of("escalated", true, "intent", analysis.intent(),
                            "confidence", analysis.confidence()),
                    List.of(Action.of("escalate", "Transfer to Human Agent"))
            );
        }

        String responseContent = generateIntentResponse(analysis);
        addToContext(responseContent);

        return new Response(
                responseContent,
                Map.of("intent", analysis.intent(), "confidence", analysis.confidence(),
                        "entities", analysis.entities()),
                analysis.suggestedActions().stream()
                        .map(a -> Action.of(a, a))
                        .toList()
        );
    }

    @Override
    public Result executeTask(Task task, ExecutionContext context) {
        if (task == null) {
            return Result.failure("Task cannot be null");
        }

        String taskType = task.type();
        if (!Set.of("conversation", "inquiry", "complaint", "sales", "support").contains(taskType)) {
            return Result.failure("Unsupported task type: " + taskType
                    + ". Supported: conversation, inquiry, complaint, sales, support");
        }

        String request = task.parameters() != null
                ? (String) task.parameters().getOrDefault("request", "")
                : "";

        Analysis analysis = analyzeRequest(request);
        Response response = generateResponse(request);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("task_id", task.id());
        output.put("task_type", taskType);
        output.put("intent", analysis.intent());
        output.put("confidence", analysis.confidence());
        output.put("response", response.content());
        output.put("escalated", response.metadata().getOrDefault("escalated", false));
        output.put("status", "completed");

        return new Result(true, output, Map.of("agent", COMPONENT_ID), List.of("Task completed successfully"));
    }

    @Override
    public Decision makeDecision(ExecutionContext context) {
        String lastMessage = conversationContext.isEmpty() ? null : conversationContext.peekLast();

        if (lastMessage == null) {
            return new Decision(
                    "greet",
                    "No conversation context; start with a greeting",
                    0.95,
                    List.of("wait", "ask_topic")
            );
        }

        Analysis analysis = intentClassifier.classify(lastMessage, intentDescriptions);

        if (analysis.confidence() < escalationThreshold) {
            return new Decision(
                    "escalate",
                    "Low confidence in intent classification; escalate to human",
                    analysis.confidence(),
                    List.of("retry_classification", "ask_clarification")
            );
        }

        return new Decision(
                "respond",
                "Intent identified as '" + analysis.intent() + "'; generate response",
                analysis.confidence(),
                List.of("escalate", "ask_clarification")
        );
    }

    @Override
    public List<Action> planActions(Goal goal, ExecutionContext context) {
        if (goal == null) {
            return List.of(Action.of("error", "No goal provided"));
        }

        List<Action> actions = new ArrayList<>();

        actions.add(new Action("classify_intent", "Classify User Intent",
                Map.of("goal", goal.description()), true));

        actions.add(new Action("extract_entities", "Extract Entities",
                Map.of("criteria", goal.successCriteria()), true));

        actions.add(new Action("route_request", "Route to Handler",
                Map.of("intents", intentDescriptions.keySet().toString()), false));

        actions.add(new Action("generate_response", "Generate Response",
                Map.of("format", "conversational"), false));

        actions.add(new Action("evaluate_satisfaction", "Evaluate Customer Satisfaction",
                Map.of("success_criteria", goal.successCriteria()), false));

        return actions;
    }

    @Override
    public void validateConfig(Map<String, Object> config) {
        if (config.containsKey("escalationThreshold")) {
            Object val = config.get("escalationThreshold");
            if (!(val instanceof Number)) {
                throw new IllegalArgumentException("escalationThreshold must be a number");
            }
            double threshold = ((Number) val).doubleValue();
            if (threshold < 0.0 || threshold > 1.0) {
                throw new IllegalArgumentException("escalationThreshold must be between 0.0 and 1.0");
            }
        }
    }

    @Override
    public void shutdown() {
        this.config = null;
        this.initialized = false;
        this.conversationContext.clear();
    }

    /**
     * Returns an unmodifiable view of the current conversation context.
     *
     * @return the recent conversation messages
     */
    public List<String> getConversationContext() {
        return List.copyOf(conversationContext);
    }

    /**
     * Sets a custom intent classifier.
     *
     * @param classifier the intent classifier to use
     */
    public void setIntentClassifier(IntentClassifier classifier) {
        this.intentClassifier = Objects.requireNonNull(classifier, "IntentClassifier cannot be null");
    }

    /**
     * Sets a custom escalation policy.
     *
     * @param policy the escalation policy to use
     */
    public void setEscalationPolicy(EscalationPolicy policy) {
        this.escalationPolicy = Objects.requireNonNull(policy, "EscalationPolicy cannot be null");
    }

    private void addToContext(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        conversationContext.addLast(message);
        while (conversationContext.size() > maxContextMessages) {
            conversationContext.removeFirst();
        }
    }

    private String generateIntentResponse(Analysis analysis) {
        String intent = analysis.intent();
        String description = intentDescriptions.getOrDefault(intent, null);

        if (description != null) {
            return "I can help you with " + description.toLowerCase() + ". " + buildFollowUp(intent);
        }

        return switch (intent) {
            case "greeting" -> "Hello! How can I assist you today?";
            case "farewell" -> "Thank you for reaching out! Is there anything else I can help with?";
            case "complaint" -> "I'm sorry to hear about your experience. Let me look into this for you.";
            case "inquiry" -> "I'd be happy to help you find that information.";
            case "purchase" -> "Great choice! Let me help you complete your purchase.";
            case "support" -> "I understand you need help. Let me assist you with that.";
            default -> "I'd be happy to help. Could you provide more details?";
        };
    }

    private String buildFollowUp(String intent) {
        return "How would you like to proceed?";
    }

    private static Analysis defaultClassify(String request, Map<String, String> intentDescriptions) {
        String lower = request.toLowerCase();
        Map<String, Object> entities = new LinkedHashMap<>();

        // Check configured intents first
        for (Map.Entry<String, String> entry : intentDescriptions.entrySet()) {
            String intentKey = entry.getKey().toLowerCase();
            String intentDesc = entry.getValue().toLowerCase();
            if (lower.contains(intentKey) || containsKeywords(lower, intentDesc)) {
                entities.put("matched_intent", entry.getKey());
                return new Analysis(entry.getKey(), entities, 0.80,
                        List.of("respond_" + entry.getKey()));
            }
        }

        // Built-in intent detection
        if (lower.matches(".*\\b(hi|hello|hey|good morning|good afternoon)\\b.*")) {
            return new Analysis("greeting", entities, 0.95, List.of("greet_back"));
        }
        if (lower.matches(".*\\b(bye|goodbye|see you|thanks|thank you)\\b.*")) {
            return new Analysis("farewell", entities, 0.90, List.of("close_conversation"));
        }
        if (lower.matches(".*\\b(problem|issue|broken|not working|complaint|angry|frustrated)\\b.*")) {
            extractComplaintEntities(lower, entities);
            return new Analysis("complaint", entities, 0.85, List.of("apologize", "investigate"));
        }
        if (lower.matches(".*\\b(buy|purchase|order|price|cost|how much)\\b.*")) {
            extractPurchaseEntities(lower, entities);
            return new Analysis("purchase", entities, 0.80, List.of("show_options", "process_order"));
        }
        if (lower.matches(".*\\b(help|support|assist|how do|how to|can you)\\b.*")) {
            return new Analysis("support", entities, 0.75, List.of("provide_help"));
        }
        if (lower.matches(".*\\b(what|where|when|who|which|tell me|info|information)\\b.*")) {
            return new Analysis("inquiry", entities, 0.70, List.of("provide_info"));
        }

        return new Analysis("unknown", entities, 0.30, List.of("ask_clarification"));
    }

    private static boolean containsKeywords(String text, String description) {
        String[] words = description.split("\\s+");
        int matches = 0;
        for (String word : words) {
            if (word.length() > 3 && text.contains(word)) {
                matches++;
            }
        }
        return matches >= 2;
    }

    private static void extractComplaintEntities(String text, Map<String, Object> entities) {
        if (text.contains("order")) entities.put("topic", "order");
        if (text.contains("delivery")) entities.put("topic", "delivery");
        if (text.contains("product")) entities.put("topic", "product");
        if (text.contains("service")) entities.put("topic", "service");
    }

    private static void extractPurchaseEntities(String text, Map<String, Object> entities) {
        if (text.contains("order")) entities.put("action", "order");
        if (text.contains("price") || text.contains("cost")) entities.put("action", "pricing");
    }

    /**
     * Builder for creating configured ConversationalAgent instances.
     */
    public static class Builder {

        private double escalationThreshold = DEFAULT_ESCALATION_THRESHOLD;
        private int maxContextMessages = DEFAULT_MAX_CONTEXT_MESSAGES;
        private final Map<String, String> intents = new LinkedHashMap<>();
        private IntentClassifier intentClassifier;
        private EscalationPolicy escalationPolicy;

        public Builder escalationThreshold(double threshold) {
            if (threshold < 0.0 || threshold > 1.0) {
                throw new IllegalArgumentException("escalationThreshold must be between 0.0 and 1.0");
            }
            this.escalationThreshold = threshold;
            return this;
        }

        public Builder maxContextMessages(int max) {
            if (max < 1) {
                throw new IllegalArgumentException("maxContextMessages must be at least 1");
            }
            this.maxContextMessages = max;
            return this;
        }

        public Builder intent(String name, String description) {
            this.intents.put(
                    Objects.requireNonNull(name, "Intent name cannot be null"),
                    Objects.requireNonNull(description, "Intent description cannot be null")
            );
            return this;
        }

        public Builder intents(Map<String, String> intents) {
            this.intents.putAll(Objects.requireNonNull(intents, "Intents map cannot be null"));
            return this;
        }

        public Builder intentClassifier(IntentClassifier classifier) {
            this.intentClassifier = Objects.requireNonNull(classifier, "IntentClassifier cannot be null");
            return this;
        }

        public Builder escalationPolicy(EscalationPolicy policy) {
            this.escalationPolicy = Objects.requireNonNull(policy, "EscalationPolicy cannot be null");
            return this;
        }

        public ConversationalAgent build() {
            ConversationalAgent agent = new ConversationalAgent();
            agent.escalationThreshold = this.escalationThreshold;
            agent.maxContextMessages = this.maxContextMessages;
            agent.intentDescriptions = new LinkedHashMap<>(this.intents);

            if (this.intentClassifier != null) {
                agent.intentClassifier = this.intentClassifier;
            }
            if (this.escalationPolicy != null) {
                agent.escalationPolicy = this.escalationPolicy;
            }

            Map<String, Object> config = new LinkedHashMap<>();
            config.put("escalationThreshold", this.escalationThreshold);
            config.put("maxContextMessages", this.maxContextMessages);
            config.put("intents", new LinkedHashMap<>(this.intents));
            agent.config = config;
            agent.initialized = true;

            return agent;
        }
    }

    /**
     * Returns a new Builder for configuring a ConversationalAgent.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }
}
