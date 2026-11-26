package br.com.archflow.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Recusa ({@code 403}) o uso autenticado da API por um tenant que o superadmin suspendeu (QA2 P6).
 *
 * <p>Até aqui "Suspender" na tela do tenant só trocava o {@code status} do cadastro para
 * {@code suspended}: nada lia o campo, e os usuários do tenant continuavam criando fluxos e executando
 * como antes. O estado já existia e a UI já o grava — faltava quem o respeitasse.
 *
 * <p>O tenant conferido é o da <em>identidade</em> — a claim do JWT, ou o tenant da chave de API —, e
 * não o tenant efetivo da requisição. A diferença está na personificação: um superadmin de outro
 * tenant que esteja "dentro" do tenant suspenso (a UI manda {@code X-Impersonate-Tenant} em toda
 * requisição) precisa continuar podendo olhá-lo e reativá-lo; se o efetivo valesse, ele se trancaria
 * do lado de fora junto com o tenant. Já o ADMIN do próprio tenant suspenso é recusado, inclusive em
 * {@code /api/admin/tenants/**}: deixá-lo passar ali seria deixá-lo se reativar.
 *
 * <p>Só conta requisição autenticada (JWT ou chave de API): sem identidade não há tenant de quem
 * recusar. Por isso as chamadas de máquina que não passam pelo filtro JWT — o
 * {@code /api/agents/invoke} do VendaX Core, com a chave de serviço da plataforma — não mudam. Com a
 * autenticação desligada (dev) também nada muda: não há identidade a conferir.
 *
 * <p>O corpo do 403 é o envelope de sempre ({@code error}, {@code message}, {@code status}), o mesmo do
 * {@link AdminRoleInterceptor} e do {@link TenantAccessDeniedHandler}.
 */
public class TenantSuspensionInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TenantSuspensionInterceptor.class);

    private final boolean authEnabled;
    private final Predicate<String> suspended;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * @param authEnabled {@code archflow.security.auth.enabled}
     * @param suspended   {@code true} para o id de um tenant suspenso no cadastro
     */
    public TenantSuspensionInterceptor(boolean authEnabled, Predicate<String> suspended) {
        this.authEnabled = authEnabled;
        this.suspended = suspended;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!authEnabled || "OPTIONS".equalsIgnoreCase(request.getMethod()) || !authenticated(request)) {
            return true;
        }
        String tenant = identityTenant(request);
        if (!suspended.test(tenant)) {
            return true;
        }
        log.warn("Denying {} {} — tenant {} is suspended", request.getMethod(), request.getRequestURI(), tenant);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write(json.writeValueAsString(Map.of(
                "error", "FORBIDDEN",
                "message", "Tenant " + tenant + " is suspended",
                "status", 403)));
        return false;
    }

    /** O que o {@link JwtAuthenticationFilter} grava para um JWT validado ou uma chave de API aceita. */
    private static boolean authenticated(HttpServletRequest request) {
        return request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID) != null
                || request.getAttribute(JwtAuthenticationFilter.ATTR_ROLES) != null
                || request.getAttribute(JwtAuthenticationFilter.ATTR_API_KEY_ID) != null;
    }

    /** O tenant da identidade; um token sem a claim é do tenant padrão, como no {@link ImpersonationFilter}. */
    private static String identityTenant(HttpServletRequest request) {
        Object claim = request.getAttribute(JwtAuthenticationFilter.ATTR_TENANT);
        return claim instanceof String s && !s.isBlank() ? s.trim() : TenantContext.DEFAULT_TENANT;
    }
}
