package br.com.archflow.agent.governance;

import java.util.*;

public record GovernanceProfile(String id, String name, String systemPrompt, Set<String> enabledTools,
                                 Set<String> disabledTools, double escalationThreshold, int maxToolExecutions,
                                 String customInstructions, Map<String, String> metadata) {
    public GovernanceProfile {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(name, "name is required");
        if (systemPrompt == null) systemPrompt = "";
        enabledTools = enabledTools == null ? Set.of() : Set.copyOf(enabledTools);
        disabledTools = disabledTools == null ? Set.of() : Set.copyOf(disabledTools);
        if (escalationThreshold < 0 || escalationThreshold > 1)
            throw new IllegalArgumentException("escalationThreshold must be 0..1");
        if (maxToolExecutions <= 0) maxToolExecutions = 10;
        if (customInstructions == null) customInstructions = "";
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public boolean isToolAllowed(String toolName) {
        if (disabledTools.contains(toolName)) return false;
        if (enabledTools.isEmpty()) return true;
        return enabledTools.contains(toolName);
    }

    public String buildSystemPrompt() {
        if (customInstructions.isBlank()) return systemPrompt;
        return systemPrompt + "\n\n" + customInstructions;
    }

    public boolean shouldEscalate(double confidence) { return confidence < escalationThreshold; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String id, name, systemPrompt = "", customInstructions = "";
        private Set<String> enabledTools = new HashSet<>(), disabledTools = new HashSet<>();
        private double escalationThreshold = 0.5;
        private int maxToolExecutions = 10;
        private Map<String, String> metadata = new HashMap<>();

        public Builder id(String id) { this.id = id; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder systemPrompt(String p) { this.systemPrompt = p; return this; }
        public Builder enableTool(String t) { this.enabledTools.add(t); return this; }
        public Builder enableTools(Set<String> t) { this.enabledTools.addAll(t); return this; }
        public Builder disableTool(String t) { this.disabledTools.add(t); return this; }
        public Builder disableTools(Set<String> t) { this.disabledTools.addAll(t); return this; }
        public Builder escalationThreshold(double t) { this.escalationThreshold = t; return this; }
        public Builder maxToolExecutions(int m) { this.maxToolExecutions = m; return this; }
        public Builder customInstructions(String i) { this.customInstructions = i; return this; }
        public Builder metadata(String k, String v) { this.metadata.put(k, v); return this; }
        public GovernanceProfile build() {
            return new GovernanceProfile(id, name, systemPrompt, enabledTools, disabledTools,
                    escalationThreshold, maxToolExecutions, customInstructions, metadata);
        }
    }
}
