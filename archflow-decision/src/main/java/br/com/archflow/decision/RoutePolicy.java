package br.com.archflow.decision;

/**
 * O que fazer com uma decisão, dado o quanto o modelo está seguro dela.
 *
 * <ul>
 *   <li>{@link Route#AUTO}: segura o bastante para o fluxo seguir sozinho;</li>
 *   <li>{@link Route#REVIEW}: provável, mas pede confirmação;</li>
 *   <li>{@link Route#ESCALATE}: incerta, ou não saiu — vai para uma pessoa ou para outro caminho.</li>
 * </ul>
 *
 * <p>Os limiares padrão (0,9 e 0,5) são os que a documentação do TypeSafe recomenda para decisões
 * de risco comum; operação destrutiva pede limiar mais alto. O fluxo escolhe, o motor não adivinha.</p>
 */
public record RoutePolicy(double auto, double review) {

    public static final RoutePolicy DEFAULT = new RoutePolicy(0.9, 0.5);

    public enum Route { AUTO, REVIEW, ESCALATE }

    public RoutePolicy {
        if (review < 0 || auto > 1 || review > auto) {
            throw new IllegalArgumentException(
                    "limiares inválidos: exige 0 <= review <= auto <= 1 (review=" + review
                            + ", auto=" + auto + ")");
        }
    }

    public Route route(double confidence) {
        if (Double.isNaN(confidence)) {
            return Route.ESCALATE;
        }
        if (confidence >= auto) {
            return Route.AUTO;
        }
        return confidence >= review ? Route.REVIEW : Route.ESCALATE;
    }
}
