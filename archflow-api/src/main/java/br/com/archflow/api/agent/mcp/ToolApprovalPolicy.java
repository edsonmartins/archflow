package br.com.archflow.api.agent.mcp;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decide quais tools exigem decisão humana antes de executar.
 *
 * <p>Terceira política do laço, e a que fecha o desenho: a
 * {@link ToolAccessPolicy} decide o que <b>pode</b> ser chamado, a
 * {@link ToolTrustPolicy} decide como <b>tratar o resultado</b>, e esta decide o
 * que precisa de <b>autorização</b>. Sem ela, o gate humano só existia como
 * aresta do grafo — dava para aprovar entre passos, não no meio de um
 * raciocínio, que é onde uma remediação de fato é proposta.
 *
 * <p>O default é {@link #none()}: nenhuma tool exige aprovação, preservando o
 * comportamento de quem já usa o laço. Exigir aprovação é uma decisão explícita
 * de quem conhece o efeito da tool.
 */
@FunctionalInterface
public interface ToolApprovalPolicy {

    boolean requiresApproval(String toolName);

    /** Nada exige aprovação (comportamento histórico). */
    static ToolApprovalPolicy none() {
        return toolName -> false;
    }

    /**
     * Exige aprovação apenas para as tools listadas — tipicamente as que
     * escrevem, reiniciam ou gastam dinheiro.
     */
    static ToolApprovalPolicy requiringFor(Collection<String> toolNames) {
        Set<String> gated = toolNames == null ? Set.of()
                : toolNames.stream()
                        .filter(n -> n != null && !n.isBlank())
                        .map(n -> n.trim().toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
        return toolName -> toolName != null
                && gated.contains(toolName.trim().toLowerCase(Locale.ROOT));
    }
}
