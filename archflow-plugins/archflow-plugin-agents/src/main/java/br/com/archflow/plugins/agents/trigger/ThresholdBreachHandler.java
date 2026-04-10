package br.com.archflow.plugins.agents.trigger;

/**
 * Handler notificado quando uma ThresholdRule é violada.
 *
 * <p>Produtos registram um handler para acionar o agente configurado
 * na regra. O motor não aciona diretamente — apenas notifica.
 */
@FunctionalInterface
public interface ThresholdBreachHandler {

    /**
     * Chamado quando um threshold é violado.
     *
     * @param rule         A regra violada
     * @param currentValue O valor atual da métrica
     */
    void onBreach(ThresholdRule rule, double currentValue);
}
