package br.com.archflow.api.agent.mcp;

import br.com.archflow.agent.governance.GovernanceProfile;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decide quais tools de um MCP server um agente pode enxergar e invocar.
 *
 * <p>É o ponto de aplicação que faltava: {@link GovernanceProfile} já carregava
 * {@code enabledTools}/{@code disabledTools} e sabia decidir
 * ({@link GovernanceProfile#isToolAllowed(String)}), mas nenhum laço de
 * tool-calling consultava essa decisão — a allowlist era configuração exibida na
 * API e ignorada em execução.
 *
 * <p>O {@link McpAgentRunner} exige uma política em toda chamada, de propósito:
 * "sem restrição" precisa ser escrito ({@link #allowAll()}), nunca resultar de
 * uma omissão. A política é consultada <b>duas vezes</b> — ao montar o catálogo
 * enviado ao modelo e novamente antes de executar a chamada — porque um modelo
 * pode emitir um nome de tool que não estava no catálogo.
 */
@FunctionalInterface
public interface ToolAccessPolicy {

    /**
     * @param toolName nome da tool como declarado pelo MCP server
     * @return {@code true} se o agente pode enxergar e invocar esta tool
     */
    boolean isAllowed(String toolName);

    /**
     * Nenhuma restrição. Use apenas quando o conjunto de tools do server já é a
     * fronteira de confiança desejada — e deixe isso explícito no call site.
     */
    static ToolAccessPolicy allowAll() {
        return toolName -> true;
    }

    /**
     * Allowlist fechada: só os nomes informados passam. Comparação
     * case-insensitive, como no registro de tools conversacional.
     */
    static ToolAccessPolicy allowOnly(Collection<String> toolNames) {
        Set<String> allowed = toolNames == null ? Set.of()
                : toolNames.stream()
                        .filter(n -> n != null && !n.isBlank())
                        .map(n -> n.trim().toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
        return toolName -> toolName != null
                && allowed.contains(toolName.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Aplica um {@link GovernanceProfile} já configurado (o mesmo objeto que a
     * API expõe em {@code GET /workflow/governance-profiles}).
     */
    static ToolAccessPolicy of(GovernanceProfile profile) {
        if (profile == null) {
            return allowAll();
        }
        return profile::isToolAllowed;
    }
}
