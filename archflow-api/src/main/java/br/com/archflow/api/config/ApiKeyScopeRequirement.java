package br.com.archflow.api.config;

import br.com.archflow.model.security.ApiKeyScope;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Que escopo de chave de API uma requisição exige (RCHFLW-84).
 *
 * <p>Os escopos de {@link ApiKeyScope} nomeiam recurso e ação ({@code workflow:execute},
 * {@code execution:read}…); esta tabela os liga aos endpoints que fazem exatamente isso. É a única
 * checagem de escopo do runtime: o filtro a aplica a toda requisição autenticada por uma chave, antes
 * de qualquer controller.
 *
 * <p>Endpoint fora da tabela não é coberto por escopo nenhum e a chave é recusada ({@code 403}) —
 * inclusive com {@link ApiKeyScope#ADMIN}, que vale por todos os escopos e não por todos os
 * endpoints. Assim uma chave nunca alcança, por omissão, o que ninguém decidiu abrir para ela: gestão
 * de chaves, administração, configuração. Abrir um endpoint novo para chaves é acrescentar uma linha
 * aqui.
 *
 * <p>Os escopos de agente e de métricas ({@code AGENT_*}, {@code METRICS_READ}) existem no vocabulário,
 * mas ainda não cobrem endpoint nenhum. {@link #opensAnyEndpoint} diz isso a quem lista os escopos,
 * para a tela não oferecer como útil uma chave que receberia {@code 403} em toda chamada.
 */
public final class ApiKeyScopeRequirement {

    private static final String SEGMENT = "[^/]+";
    private static final Pattern WORKFLOWS = Pattern.compile("/api/workflows");
    private static final Pattern WORKFLOW_EXECUTE = Pattern.compile("/api/workflows/" + SEGMENT + "/execute");
    private static final Pattern WORKFLOW_SUBTREE = Pattern.compile("/api/workflows/.+");
    private static final Pattern EXECUTIONS = Pattern.compile("/api/executions(/.+)?");
    /** Execução em streaming de um workflow (o trecho "Streaming" do Publicar). */
    private static final Pattern AG_UI_WORKFLOW = Pattern.compile("/ag-ui/workflows/" + SEGMENT);
    /** Servidor MCP: expõe os workflows do tenant como ferramentas; {@code tools/call} os executa. */
    private static final Pattern MCP = Pattern.compile("/mcp");

    /**
     * Os escopos que {@link #requiredScope} devolve para algum endpoint — exatamente os dos ramos
     * abaixo. Ao abrir um endpoint para um escopo novo, ele entra aqui também
     * ({@code ApiKeyScopeRequirementTest} confere as duas listas).
     */
    private static final Set<ApiKeyScope> COVERED = EnumSet.of(
            ApiKeyScope.WORKFLOW_READ, ApiKeyScope.WORKFLOW_CREATE, ApiKeyScope.WORKFLOW_UPDATE,
            ApiKeyScope.WORKFLOW_DELETE, ApiKeyScope.WORKFLOW_EXECUTE,
            ApiKeyScope.EXECUTION_READ, ApiKeyScope.EXECUTION_MANAGE);

    private ApiKeyScopeRequirement() {
    }

    /**
     * True quando uma chave com este escopo alcança algum endpoint. {@link ApiKeyScope#ADMIN} vale por
     * todos os escopos, logo alcança tudo o que eles alcançam.
     */
    public static boolean opensAnyEndpoint(ApiKeyScope scope) {
        return scope != null && (scope.isAdmin() || COVERED.contains(scope));
    }

    /**
     * @return o escopo que a requisição exige, ou vazio quando nenhum escopo cobre o endpoint
     */
    public static Optional<ApiKeyScope> requiredScope(String method, String path) {
        if (method == null || path == null || !isCanonical(path)) {
            return Optional.empty();
        }
        String verb = method.toUpperCase(java.util.Locale.ROOT);
        if (WORKFLOWS.matcher(path).matches()) {
            return switch (verb) {
                case "GET" -> Optional.of(ApiKeyScope.WORKFLOW_READ);
                case "POST" -> Optional.of(ApiKeyScope.WORKFLOW_CREATE);
                default -> Optional.empty();
            };
        }
        if (WORKFLOW_EXECUTE.matcher(path).matches()) {
            return "POST".equals(verb) ? Optional.of(ApiKeyScope.WORKFLOW_EXECUTE) : Optional.empty();
        }
        if (WORKFLOW_SUBTREE.matcher(path).matches()) {
            return switch (verb) {
                case "GET" -> Optional.of(ApiKeyScope.WORKFLOW_READ);
                // PUT do documento e do YAML, PATCH do status, POST de publicar.
                case "PUT", "PATCH", "POST" -> Optional.of(ApiKeyScope.WORKFLOW_UPDATE);
                case "DELETE" -> Optional.of(ApiKeyScope.WORKFLOW_DELETE);
                default -> Optional.empty();
            };
        }
        if (EXECUTIONS.matcher(path).matches()) {
            return switch (verb) {
                case "GET" -> Optional.of(ApiKeyScope.EXECUTION_READ);
                // cancelar e retomar
                case "POST" -> Optional.of(ApiKeyScope.EXECUTION_MANAGE);
                default -> Optional.empty();
            };
        }
        if (AG_UI_WORKFLOW.matcher(path).matches() || MCP.matcher(path).matches()) {
            return "POST".equals(verb) ? Optional.of(ApiKeyScope.WORKFLOW_EXECUTE) : Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * O URI cru ({@code getRequestURI}) não vem normalizado nem decodificado: {@code /api/workflows/x/../../apikeys}
     * casaria com a árvore de workflows aqui e seria roteado para outro lugar pelo servidor. Caminho
     * que não está na forma canônica não é coberto por escopo nenhum.
     */
    private static boolean isCanonical(String path) {
        return path.startsWith("/")
                && !path.contains("//")
                && !path.contains("/./")
                && !path.contains("/../")
                && !path.endsWith("/.")
                && !path.endsWith("/..")
                && !path.contains("%")
                && !path.contains(";")
                && !path.contains("\\");
    }
}
