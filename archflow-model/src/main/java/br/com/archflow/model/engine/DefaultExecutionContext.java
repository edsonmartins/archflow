package br.com.archflow.model.engine;

import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.metrics.StepMetrics;
import dev.langchain4j.memory.ChatMemory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultExecutionContext implements ExecutionContext {
    private final Map<String, Object> variables;
    private final ChatMemory chatMemory;
    private final Map<String, StepMetrics> stepMetricsMap;
    private long startTime;
    private int totalTokens;
    private double estimatedCost;
    private FlowState state;

    public DefaultExecutionContext(ChatMemory chatMemory) {
        this.variables = new HashMap<>();
        this.chatMemory = chatMemory;
        this.stepMetricsMap = new ConcurrentHashMap<>();
        this.startTime = System.currentTimeMillis();
        this.totalTokens = 0;
        this.estimatedCost = 0.0;
    }

    @Override
    public Optional<Object> get(String key) {
        return Optional.ofNullable(variables.get(key));
    }

    @Override
    public void set(String key, Object value) {
        variables.put(key, value);
    }

    @Override
    public ChatMemory getChatMemory() {
        return chatMemory;
    }

    @Override
    public ExecutionMetrics getMetrics() {
        return new ExecutionMetrics(
                System.currentTimeMillis() - startTime,
                totalTokens,
                estimatedCost,
                Map.copyOf(stepMetricsMap)
        );
    }

    @Override
    public FlowState getState() {
        return state;
    }

    @Override
    public void setState(FlowState state) {
        this.state = state;
    }

    /**
     * Adiciona métricas de um passo específico
     */
    public void addStepMetrics(String stepId, StepMetrics metrics) {
        stepMetricsMap.put(stepId, metrics);
        totalTokens += metrics.tokensUsed();

        // Calcula custo estimado baseado em tokens (pode ser customizado)
        // Exemplo: $0.002 por token
        estimatedCost += (metrics.tokensUsed() * 0.002);
    }

    /**
     * Reseta o tempo de início da execução
     */
    public void resetStartTime() {
        this.startTime = System.currentTimeMillis();
    }

    /**
     * Atualiza o custo estimado diretamente
     */
    public void updateEstimatedCost(double cost) {
        this.estimatedCost = cost;
    }
}