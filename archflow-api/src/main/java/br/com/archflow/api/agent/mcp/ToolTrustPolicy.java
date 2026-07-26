package br.com.archflow.api.agent.mcp;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decide o {@link ToolTrust} do resultado de cada tool.
 *
 * <p>O default deliberado é {@link #untrustedByDefault()}: o conteúdo veio de
 * outro processo, então tratá-lo como dado é a posição segura, e marcar uma
 * tool como confiável exige uma afirmação explícita de quem conhece o contrato
 * dela. O contrário — confiar por omissão — é o comportamento que transforma
 * uma linha de log em instrução.
 *
 * @see ToolAccessPolicy política irmã, que decide o que pode ser <em>invocado</em>
 */
@FunctionalInterface
public interface ToolTrustPolicy {

    ToolTrust trustOf(String toolName);

    /** Tudo que vem do server é dado não-confiável. */
    static ToolTrustPolicy untrustedByDefault() {
        return toolName -> ToolTrust.UNTRUSTED;
    }

    /**
     * Marca como confiáveis apenas as tools informadas; o resto segue
     * não-confiável. Use quando o payload de certas tools é estruturado e
     * gerado pelo próprio sistema de domínio.
     */
    static ToolTrustPolicy trusting(Collection<String> trustedToolNames) {
        Set<String> trusted = trustedToolNames == null ? Set.of()
                : trustedToolNames.stream()
                        .filter(n -> n != null && !n.isBlank())
                        .map(n -> n.trim().toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
        return toolName -> toolName != null
                && trusted.contains(toolName.trim().toLowerCase(Locale.ROOT))
                ? ToolTrust.TRUSTED
                : ToolTrust.UNTRUSTED;
    }

    /**
     * Todo resultado é confiável. Só é correto quando o MCP server inteiro é
     * parte do sistema e nenhum campo do retorno carrega texto de terceiros —
     * declare no call site por que isso vale.
     */
    static ToolTrustPolicy trustAll() {
        return toolName -> ToolTrust.TRUSTED;
    }
}
