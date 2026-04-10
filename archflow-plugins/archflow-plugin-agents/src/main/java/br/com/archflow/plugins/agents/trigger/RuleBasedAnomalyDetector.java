package br.com.archflow.plugins.agents.trigger;

import br.com.archflow.plugins.agents.MonitoringAgent.AnomalyDetector;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Detector de anomalias baseado em regras genéricas ({@link ThresholdRule}).
 *
 * <p>Substitui o {@code ThresholdAnomalyDetector} hardcoded por uma
 * implementação que aceita regras dinâmicas por tenant.
 *
 * <p>Suporta cooldown por regra para evitar alertas repetidos.
 */
public class RuleBasedAnomalyDetector implements AnomalyDetector {
    private static final Logger logger = Logger.getLogger(RuleBasedAnomalyDetector.class.getName());

    private final Map<String, ThresholdRule> rules = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastTriggered = new ConcurrentHashMap<>();
    private final MetricProvider metricProvider;
    private final ThresholdBreachHandler breachHandler;

    public RuleBasedAnomalyDetector(MetricProvider metricProvider) {
        this(metricProvider, null);
    }

    public RuleBasedAnomalyDetector(MetricProvider metricProvider, ThresholdBreachHandler breachHandler) {
        this.metricProvider = metricProvider;
        this.breachHandler = breachHandler;
    }

    /**
     * Registra uma regra de threshold.
     */
    public void addRule(ThresholdRule rule) {
        rules.put(ruleKey(rule.tenantId(), rule.ruleId()), rule);
    }

    /**
     * Remove uma regra.
     */
    public boolean removeRule(String tenantId, String ruleId) {
        return rules.remove(ruleKey(tenantId, ruleId)) != null;
    }

    /**
     * Lista regras de um tenant.
     */
    public List<ThresholdRule> listRules(String tenantId) {
        String prefix = tenantId + ":";
        return rules.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .map(Map.Entry::getValue)
                .toList();
    }

    @Override
    public boolean isAnomaly(String metricName, double value, List<Double> history) {
        // Verifica todas as regras que monitoram esta métrica
        boolean anomalyDetected = false;
        for (ThresholdRule rule : rules.values()) {
            if (rule.metricId().equals(metricName) && rule.isBreached(value)) {
                if (isCooldownExpired(rule)) {
                    anomalyDetected = true;
                    lastTriggered.put(ruleKey(rule.tenantId(), rule.ruleId()), Instant.now());
                    logger.info(() -> String.format(
                            "Threshold breached: tenant=%s, rule=%s, metric=%s, value=%.2f %s %.2f",
                            rule.tenantId(), rule.ruleId(), metricName,
                            value, rule.operator(), rule.threshold()));
                }
            }
        }
        return anomalyDetected;
    }

    /**
     * Avalia todas as regras de um tenant contra métricas atuais do MetricProvider.
     *
     * @param tenantId ID do tenant
     * @return Lista de regras que foram violadas
     */
    public List<ThresholdRule> evaluateRules(String tenantId) {
        List<ThresholdRule> breached = new java.util.ArrayList<>();
        for (ThresholdRule rule : listRules(tenantId)) {
            double value = metricProvider.getMetric(tenantId, rule.metricId(), Map.of());
            if (rule.isBreached(value) && isCooldownExpired(rule)) {
                lastTriggered.put(ruleKey(rule.tenantId(), rule.ruleId()), Instant.now());
                breached.add(rule);

                if (breachHandler != null) {
                    try {
                        breachHandler.onBreach(rule, value);
                    } catch (Exception e) {
                        logger.log(java.util.logging.Level.WARNING,
                                "Error in breach handler for rule " + rule.ruleId(), e);
                    }
                }
            }
        }
        return breached;
    }

    private boolean isCooldownExpired(ThresholdRule rule) {
        String key = ruleKey(rule.tenantId(), rule.ruleId());
        Instant last = lastTriggered.get(key);
        if (last == null) return true;
        return Instant.now().isAfter(last.plusSeconds(rule.cooldownSeconds()));
    }

    private static String ruleKey(String tenantId, String ruleId) {
        return tenantId + ":" + ruleId;
    }
}
