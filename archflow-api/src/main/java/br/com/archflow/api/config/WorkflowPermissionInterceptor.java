package br.com.archflow.api.config;

import br.com.archflow.model.security.ApiKeyScope;
import br.com.archflow.model.security.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
        Optional<String> required = requiredPermission(request.getMethod(), lookupPath(request));
        if (required.isEmpty()) {
            return true;
        }
        Object roles = request.getAttribute(JwtAuthenticationFilter.ATTR_ROLES);
        List<String> roleNames = new ArrayList<>();
        if (roles instanceof Collection<?> collection) {
            collection.forEach(role -> roleNames.add(String.valueOf(role)));
        }
        if (permits(roleNames, required.get())) {
            return true;
        }
        log.warn("Denying {} {} — requires {} (roles: {})",
                request.getMethod(), request.getRequestURI(), required.get(), roleNames);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "FORBIDDEN");
        body.put("message", "This action requires the permission " + required.get()
                + ", which the current roles " + roleNames + " do not grant");
        body.put("permission", required.get());
        body.put("status", 403);
        response.getWriter().write(json.writeValueAsString(body));
        return false;
    }

    /**
     * O mesmo caminho com que o Spring casou os padrões do interceptor e o handler. Ler o
     * {@code getRequestURI} cru permitiria que interceptor e roteamento enxergassem caminhos
     * diferentes.
     */
    private static String lookupPath(HttpServletRequest request) {
        if (!ServletRequestPathUtils.hasCachedPath(request)) {
            ServletRequestPathUtils.parseAndCache(request);
        }
        return ServletRequestPathUtils.getCachedPathValue(request);
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
     * </ul>
     *
     * <p>Toda escrita sob esses prefixos cai num ramo: um endpoint novo de escrita nasce protegido
     * (no mínimo {@code workflow:update}), em vez de nascer aberto.
     */
    static Optional<String> requiredPermission(String method, String path) {
        if (method == null || path == null) {
            return Optional.empty();
        }
        String verb = method.toUpperCase(Locale.ROOT);
        // Preflight CORS não carrega Authorization; leituras ficam só com a autenticação.
        if ("GET".equals(verb) || "HEAD".equals(verb) || "OPTIONS".equals(verb)) {
            return Optional.empty();
        }
        List<String> segments = segments(path);
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
        return Optional.empty();
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
