package br.com.archflow.api.config;

import br.com.archflow.model.security.ApiKeyScope;
import br.com.archflow.model.security.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ServletRequestPathUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * RBAC de fluxos e execuções (RCHFLW-74): cada escrita em {@code /api/workflows/**},
 * {@code /api/executions/**}, a execução por {@code /ag-ui/workflows/{id}} e a instalação de template
 * (que cria um fluxo) exigem a permissão que os papéis built-in de {@link Role} já declaram. Sem ela,
 * {@code 403} com corpo JSON dizendo qual permissão faltou.
 *
 * <p>Antes só {@code /api/admin/global|tenants} tinha checagem de papel ({@link AdminRoleInterceptor});
 * aqui qualquer usuário autenticado — inclusive um VIEWER — criava, editava, apagava, publicava e
 * executava fluxos (gastando LLM do tenant). O padrão é o mesmo daquele interceptor: os papéis vêm do
 * atributo {@link JwtAuthenticationFilter#ATTR_ROLES}, populado pelo filtro JWT antes do MVC.
 *
 * <p>A matriz não é nova: cada papel é a definição de {@link Role#createViewerRole()},
 * {@link Role#createExecutorRole()} etc., e o nome de cada permissão é o de {@link ApiKeyScope} — o
 * mesmo vocabulário que limita uma chave de API, para que "o que este endpoint exige" tenha um nome só.
 * Por isso cancelar/retomar uma execução exige {@code execution:manage} ("Cancel running executions"):
 * nenhum papel além do ADMIN a declara hoje, logo só o ADMIN cancela ou retoma.
 *
 * <p>QA2 (P1–P4) estendeu a mesma tabela às outras escritas que estavam só com autenticação: decidir
 * aprovações, o servidor {@code /mcp}, os endpoints que acionam agente/LLM, conversas, gatilhos, bases
 * de conhecimento, arquivos, jobs e skills — ver {@link #requiredPermission}. Nenhum papel nem
 * permissão novos: quando nenhuma permissão existente descreve a ação, vale a mais próxima e mais
 * restritiva.
 *
 * <p>Leituras ({@code GET}/{@code HEAD}) não são checadas aqui: continuam exigindo só autenticação.
 * Os papéis DESIGNER e EXECUTOR não declaram {@code execution:read}; aplicá-la tiraria deles o
 * histórico do que eles mesmos executam — decisão de papel, não deste interceptor.
 *
 * <p>Com {@code archflow.security.auth.enabled=false} (dev/E2E) deixa passar, como o
 * {@link AdminRoleInterceptor}; em produção o {@link ProductionReadinessGuard} recusa o boot assim.
 */
public class WorkflowPermissionInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WorkflowPermissionInterceptor.class);

    private final boolean authEnabled;
    private final ObjectMapper json = new ObjectMapper();

    public WorkflowPermissionInterceptor(boolean authEnabled) {
        this.authEnabled = authEnabled;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!authEnabled) {
            return true;
        }
        Optional<String> required = requiredPermission(request.getMethod(), lookupSegments(request));
        if (required.isEmpty()) {
            return true;
        }
        Optional<Map<String, Object>> refusal = refusal(request, required.get());
        if (refusal.isEmpty()) {
            return true;
        }
        log.warn("Denying {} {} — {}", request.getMethod(), request.getRequestURI(), refusal.get().get("message"));
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write(json.writeValueAsString(refusal.get()));
        return false;
    }

    /**
     * O corpo do {@code 403} quando a requisição não tem a permissão, ou vazio quando tem. Público para
     * o controller que só sabe a permissão depois de ler o recurso (o envio de formulário que decide um
     * portão de aprovação) recusar com o mesmo corpo deste interceptor.
     *
     * <p>Uma requisição autenticada por chave de API não traz papéis: quem a limita são os escopos,
     * que o {@link JwtAuthenticationFilter} já conferiu (junto com o papel atual do dono) para este
     * método e caminho. Aqui eles são conferidos de novo contra a permissão, no mesmo vocabulário — sem
     * isso a chave caía na regra dos papéis, com lista vazia, e recebia {@code 403} justamente nos
     * endpoints que o escopo dela abre ({@code POST /api/workflows/{id}/execute}, {@code POST /mcp}).
     */
    public static Optional<Map<String, Object>> refusal(HttpServletRequest request, String permission) {
        if (request != null && request.getAttribute(JwtAuthenticationFilter.ATTR_API_KEY_ID) != null) {
            if (scopesGrant(request.getAttribute(JwtAuthenticationFilter.ATTR_API_KEY_SCOPES), permission)) {
                return Optional.empty();
            }
            return Optional.of(forbidden(permission, "This action requires the permission " + permission
                    + ", which the scopes of this API key do not grant"));
        }
        // Sem requisição não há identidade: nenhum papel, logo nenhuma permissão.
        Object roles = request == null ? null : request.getAttribute(JwtAuthenticationFilter.ATTR_ROLES);
        List<String> roleNames = new ArrayList<>();
        if (roles instanceof Collection<?> collection) {
            collection.forEach(role -> roleNames.add(String.valueOf(role)));
        }
        if (permits(roleNames, permission)) {
            return Optional.empty();
        }
        return Optional.of(forbidden(permission, "This action requires the permission " + permission
                + ", which the current roles " + roleNames + " do not grant"));
    }

    private static Map<String, Object> forbidden(String permission, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "FORBIDDEN");
        body.put("message", message);
        body.put("permission", permission);
        body.put("status", 403);
        return body;
    }

    /** True quando um dos escopos da chave é a permissão, ou é {@link ApiKeyScope#ADMIN}. */
    private static boolean scopesGrant(Object scopes, String permission) {
        if (!(scopes instanceof Collection<?> collection)) {
            return false;
        }
        for (Object scope : collection) {
            if (scope instanceof ApiKeyScope s && (s.isAdmin() || s.getPermission().equals(permission))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Os segmentos do caminho como o Spring MVC os casa: decodificados e sem parâmetros de matriz
     * ({@code ;}), o {@link PathContainer.PathSegment#valueToMatch()} de cada um. Não basta o
     * {@code getCachedPathValue}: ele tira o {@code ;}, mas devolve o caminho ainda percent-encoded,
     * enquanto o roteamento e o padrão deste interceptor casam o decodificado. Com ele,
     * {@code POST /api/approv%61ls/x} chegava ao controller de aprovações sem casar regra nenhuma aqui
     * — um VIEWER (ou, com {@code /%61pi/...}, que o filtro JWT nem trata como protegido, alguém sem
     * token) decidia aprovações, executava pelo {@code /mcp} etc.
     *
     * <p>Fora de uma requisição já roteada pelo {@code DispatcherServlet} (que deixa o caminho
     * analisado em cache), o caminho é analisado aqui mesmo, sem gravar nada na requisição.
     */
    private static List<String> lookupSegments(HttpServletRequest request) {
        RequestPath path = ServletRequestPathUtils.hasParsedRequestPath(request)
                ? ServletRequestPathUtils.getParsedRequestPath(request)
                : RequestPath.parse(request.getRequestURI(), request.getContextPath());
        List<String> out = new ArrayList<>();
        for (PathContainer.Element element : path.pathWithinApplication().elements()) {
            if (element instanceof PathContainer.PathSegment segment && !segment.valueToMatch().isEmpty()) {
                out.add(segment.valueToMatch());
            }
        }
        return out;
    }

    /**
     * A permissão que uma requisição a fluxos/execuções exige, ou vazio para uma leitura.
     *
     * <ul>
     *   <li>{@code POST /api/workflows} → {@code workflow:create}</li>
     *   <li>{@code POST /api/workflows/{id}/execute} e {@code POST /ag-ui/workflows/{id}} →
     *       {@code workflow:execute}</li>
     *   <li>{@code DELETE /api/workflows/{id}} → {@code workflow:delete}</li>
     *   <li>demais escritas em {@code /api/workflows/**} (PUT do documento e do YAML, PATCH do status,
     *       publicar, deployments) → {@code workflow:update}</li>
     *   <li>escritas em {@code /api/executions/**} (cancelar, retomar) → {@code execution:manage}</li>
     *   <li>{@code POST /api/admin/observability/running/{flowId}/cancel} → {@code execution:manage}: o
     *       mesmo cancelar, por outra rota</li>
     *   <li>{@code POST /api/templates/{id}/install} → {@code workflow:create}: grava um fluxo em
     *       rascunho, a mesma ação do {@code POST /api/workflows} — sem isso o VIEWER criava fluxos por
     *       aqui. Só esta rota de templates: as demais (inclusive o {@code POST} que só calcula os nós
     *       sem executor) não gravam nada.</li>
     *   <li>{@code POST /api/approvals/{id}} e {@code /archflow/approvals/{id}} (decidir) →
     *       {@code execution:manage}: o mesmo retomar/encerrar de {@code /api/executions/{id}/resume}</li>
     *   <li>{@code POST /mcp} → {@code workflow:execute}, como a regra da chave de API</li>
     *   <li>escritas que acionam agente/LLM — {@code /archflow/agents/**}, {@code /archflow/events/**},
     *       {@code /ag-ui/agent}, {@code /api/orchestration/**}, {@code /api/agents/qp/**} — e as de
     *       {@code /api/conversations/**} → {@code workflow:execute}</li>
     *   <li>{@code POST /api/admin/triggers/{id}/fire} → {@code workflow:execute}; demais escritas de
     *       gatilhos → {@code workflow:update}</li>
     *   <li>escritas em {@code /api/knowledge-bases/**} (menos o {@code POST .../search}, que só lê) e em
     *       {@code /api/files/**} → {@code workflow:update}</li>
     *   <li>escritas em {@code /api/jobs/**} → {@code execution:manage}</li>
     *   <li>escritas em {@code /api/admin/skills/**} → {@code agent:manage}</li>
     * </ul>
     *
     * <p>Toda escrita sob esses prefixos cai num ramo: um endpoint novo de escrita nasce protegido
     * (no mínimo {@code workflow:update}), em vez de nascer aberto.
     */
    static Optional<String> requiredPermission(String method, String path) {
        return path == null ? Optional.empty() : requiredPermission(method, segments(path));
    }

    /** O mesmo, sobre os segmentos já decodificados ({@link #lookupSegments}). */
    private static Optional<String> requiredPermission(String method, List<String> segments) {
        if (method == null) {
            return Optional.empty();
        }
        String verb = method.toUpperCase(Locale.ROOT);
        // Preflight CORS não carrega Authorization; leituras ficam só com a autenticação.
        if ("GET".equals(verb) || "HEAD".equals(verb) || "OPTIONS".equals(verb)) {
            return Optional.empty();
        }
        if (startsWith(segments, "api", "workflows")) {
            if (segments.size() == 2) {
                return Optional.of(("POST".equals(verb) ? ApiKeyScope.WORKFLOW_CREATE
                        : ApiKeyScope.WORKFLOW_UPDATE).getPermission());
            }
            if (segments.size() == 4 && "execute".equals(segments.get(3)) && "POST".equals(verb)) {
                return Optional.of(ApiKeyScope.WORKFLOW_EXECUTE.getPermission());
            }
            return Optional.of(("DELETE".equals(verb) ? ApiKeyScope.WORKFLOW_DELETE
                    : ApiKeyScope.WORKFLOW_UPDATE).getPermission());
        }
        if (startsWith(segments, "api", "executions")) {
            return Optional.of(ApiKeyScope.EXECUTION_MANAGE.getPermission());
        }
        if (startsWith(segments, "ag-ui", "workflows")) {
            return Optional.of(ApiKeyScope.WORKFLOW_EXECUTE.getPermission());
        }
        // O cancelamento pela observabilidade é a mesma ação do cancelar em /api/executions: sem esta
        // regra, um VIEWER cancelava execuções do próprio tenant por aqui, contornando o execution:manage.
        if (segments.size() == 6 && startsWith(segments, "api", "admin")
                && "observability".equals(segments.get(2)) && "running".equals(segments.get(3))
                && "cancel".equals(segments.get(5)) && "POST".equals(verb)) {
            return Optional.of(ApiKeyScope.EXECUTION_MANAGE.getPermission());
        }
        if (startsWith(segments, "api", "templates") && segments.size() == 4
                && "install".equals(segments.get(3)) && "POST".equals(verb)) {
            return Optional.of(ApiKeyScope.WORKFLOW_CREATE.getPermission());
        }
        // Decidir uma aprovação retoma ou encerra a execução parada no portão: é a mesma ação do
        // retomar em /api/executions. Sem a regra, qualquer autenticado — inclusive um VIEWER — aprovava,
        // rejeitava ou editava o payload que o fluxo segue usando (QA2 P1).
        if (startsWith(segments, "api", "approvals") || startsWith(segments, "archflow", "approvals")) {
            return Optional.of(ApiKeyScope.EXECUTION_MANAGE.getPermission());
        }
        // O servidor MCP inteiro, como a regra da chave de API (ApiKeyScopeRequirement): o interceptor só
        // vê o caminho, e o tools/call executa o fluxo — o VIEWER executava por aqui o que o
        // POST /api/workflows/{id}/execute lhe recusa (QA2 P3).
        if (segments.size() == 1 && "mcp".equals(segments.get(0))) {
            return Optional.of(ApiKeyScope.WORKFLOW_EXECUTE.getPermission());
        }
        if (runsAnAgent(segments)) {
            return Optional.of(ApiKeyScope.WORKFLOW_EXECUTE.getPermission());
        }
        if (startsWith(segments, "api", "conversations")) {
            // Conversar é acionar o agente (cada mensagem dispara a resposta dele). O envio de um
            // formulário que continua uma execução exige, além disto, execution:manage — conferido no
            // controller, que é quem sabe se a conversa está ligada a uma execução.
            return Optional.of(ApiKeyScope.WORKFLOW_EXECUTE.getPermission());
        }
        if (segments.size() >= 3 && startsWith(segments, "api", "admin") && "triggers".equals(segments.get(2))) {
            // Disparar agora é executar o fluxo; criar/editar/apagar o agendamento é configurar quando ele
            // roda — o equivalente aos deployments de /api/workflows/{id}, que exigem workflow:update.
            boolean fire = segments.size() == 5 && "fire".equals(segments.get(4));
            return Optional.of((fire ? ApiKeyScope.WORKFLOW_EXECUTE : ApiKeyScope.WORKFLOW_UPDATE)
                    .getPermission());
        }
        if (startsWith(segments, "api", "knowledge-bases")) {
            // A busca é um POST que só lê (o corpo carrega a consulta).
            if (segments.size() == 4 && "search".equals(segments.get(3)) && "POST".equals(verb)) {
                return Optional.empty();
            }
            return Optional.of(ApiKeyScope.WORKFLOW_UPDATE.getPermission());
        }
        if (startsWith(segments, "api", "files")) {
            // Os arquivos são a matéria-prima das bases de conhecimento: mesma permissão de editá-las.
            return Optional.of(ApiKeyScope.WORKFLOW_UPDATE.getPermission());
        }
        if (startsWith(segments, "api", "jobs")) {
            // Submeter ou cancelar um job em segundo plano é gerir execuções; a UI só lê jobs (o ingest
            // da base de conhecimento os cria no servidor, pelo KnowledgeService).
            return Optional.of(ApiKeyScope.EXECUTION_MANAGE.getPermission());
        }
        if (segments.size() >= 3 && startsWith(segments, "api", "admin") && "skills".equals(segments.get(2))) {
            // Ativar/desativar skills muda o que os agentes do tenant fazem.
            return Optional.of(ApiKeyScope.AGENT_MANAGE.getPermission());
        }
        return Optional.empty();
    }

    /**
     * Escritas que põem um agente/LLM para rodar no tenant: o invoke e a mensagem de evento do
     * {@code /archflow} (enfileiram uma invocação), o agente AG-UI, a orquestração dinâmica e o agente
     * QP. Não inclui {@code /api/agents/invoke/**}: é a chamada de máquina do VendaX Core, fora do JWT,
     * autenticada pela chave de serviço no próprio controller.
     */
    private static boolean runsAnAgent(List<String> segments) {
        return startsWith(segments, "archflow", "agents")
                || startsWith(segments, "archflow", "events")
                || (segments.size() == 2 && "ag-ui".equals(segments.get(0)) && "agent".equals(segments.get(1)))
                || startsWith(segments, "api", "orchestration")
                || (segments.size() >= 3 && startsWith(segments, "api", "agents") && "qp".equals(segments.get(2)));
    }

    /**
     * True quando algum dos papéis concede a permissão ({@code recurso:acao}). ADMIN/SUPERADMIN
     * (com ou sem {@code ROLE_}) concedem tudo, como no {@link AdminRoleInterceptor}; os demais são
     * as definições built-in de {@link Role}. Papel desconhecido não concede nada.
     */
    static boolean permits(Collection<String> roles, String permission) {
        for (String role : roles) {
            if (AdminRoleInterceptor.isAdminRole(role)) {
                return true;
            }
            Role builtIn = builtInRole(role);
            if (builtIn != null && builtIn.hasPermission(permission)) {
                return true;
            }
        }
        return false;
    }

    private static Role builtInRole(String role) {
        if (role == null) {
            return null;
        }
        String name = role.trim().toUpperCase(Locale.ROOT);
        if (name.startsWith("ROLE_")) {
            name = name.substring(5);
        }
        return switch (name) {
            case Role.ROLE_DESIGNER -> Role.createDesignerRole();
            case Role.ROLE_EXECUTOR -> Role.createExecutorRole();
            case Role.ROLE_VIEWER -> Role.createViewerRole();
            default -> null;
        };
    }

    private static List<String> segments(String path) {
        List<String> out = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (!segment.isEmpty()) {
                out.add(segment);
            }
        }
        return out;
    }

    private static boolean startsWith(List<String> segments, String first, String second) {
        return segments.size() >= 2 && first.equals(segments.get(0)) && second.equals(segments.get(1));
    }
}
