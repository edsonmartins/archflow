package br.com.archflow.model.engine;

import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.metrics.StepMetrics;
import dev.langchain4j.memory.ChatMemory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementação mutável legada do ExecutionContext.
 *
 * @deprecated Use {@link ImmutableExecutionContext} para novos desenvolvimentos.
 *             Esta classe é mantida apenas para backward compatibility com código existente.
 *             Em produção multi-tenant, prefira o record imutável que garante
 *             isolamento seguro em execuções paralelas.
 */
@Deprecated
public class DefaultExecutionContext implements ExecutionContext {
    private final Map<String, Object> variables;
    private final ChatMemory chatMemory;
    private final Map<String, StepMetrics> stepMetricsMap;
    private final String tenantId;
    private final String userId;
    private final String sessionId;
    private final String requestId;
    private long startTime;
    private int totalTokens;
    private double estimatedCost;
    private FlowState state;

    public DefaultExecutionContext(ChatMemory chatMemory) {
        this("SYSTEM", null, null, chatMemory);
    }

    public DefaultExecutionContext(String tenantId, String userId, String sessionId, ChatMemory chatMemory) {
        this.tenantId = tenantId != null ? tenantId : "SYSTEM";
        this.userId = userId;
        this.sessionId = sessionId;
        this.requestId = UUID.randomUUID().toString();
        this.variables = new ConcurrentHashMap<>();
        this.chatMemory = chatMemory;
        this.stepMetricsMap = new ConcurrentHashMap<>();
        this.startTime = System.currentTimeMillis();
        this.totalTokens = 0;
        this.estimatedCost = 0.0;
    }

    private DefaultExecutionContext(String tenantId, String userId, String sessionId, String requestId,
                                    Map<String, Object> variables, ChatMemory chatMemory,
                                    Map<String, StepMetrics> stepMetricsMap,
                                    long startTime, int totalTokens, double estimatedCost, FlowState state) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.sessionId = sessionId;
        this.requestId = requestId;
        this.variables = variables;
        this.chatMemory = chatMemory;
        this.stepMetricsMap = stepMetricsMap;
        this.startTime = startTime;
        this.totalTokens = totalTokens;
        this.estimatedCost = estimatedCost;
        this.state = state;
    }

    @Override
    public Optional<Object> get(String key) {
        return Optional.ofNullable(variables.get(key));
    }

    @SuppressWarnings("deprecation")
    @Override
    public void set(String key, Object value) {
        variables.put(key, value);
    }

    @Override
    public ExecutionContext withVariable(String key, Object value) {
        Map<String, Object> newVars = new HashMap<>(this.variables);
        newVars.put(key, value);
        return new DefaultExecutionContext(
                tenantId, userId, sessionId, requestId,
                new ConcurrentHashMap<>(newVars),
                chatMemory, new ConcurrentHashMap<>(stepMetricsMap),
                startTime, totalTokens, estimatedCost, state
        );
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

    @Override
    public String getTenantId() {
        return tenantId;
    }

    @Override
    public String getUserId() {
        return userId;
    }

    @Override
    public String getSessionId() {
        return sessionId;
    }

    @Override
    public String getRequestId() {
        return requestId;
    }

    @Override
    public Map<String, Object> getVariables() {
        return Collections.unmodifiableMap(variables);
    }

    @Override
    public ExecutionContext snapshot() {
        return new DefaultExecutionContext(
                tenantId, userId, sessionId, requestId,
                Collections.unmodifiableMap(new HashMap<>(variables)),
                chatMemory,
                new ConcurrentHashMap<>(stepMetricsMap),
                startTime, totalTokens, estimatedCost, state
        );
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