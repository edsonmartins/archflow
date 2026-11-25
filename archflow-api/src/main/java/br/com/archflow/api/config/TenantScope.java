package br.com.archflow.api.config;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collection;

/**
 * Decide de quais tenants uma requisição pode ler ou decidir itens (RCHFLW-39).
 *
 * <p>Aprovações, observabilidade e orquestração recebiam o {@code tenantId} do cliente (query ou
 * corpo) e o repassavam direto: sem o parâmetro, ou com {@code "all"}, qualquer usuário autenticado
 * lia e decidia itens de todos os tenants. O tenant agora vem da identidade — o que o
 * {@link ImpersonationFilter} gravou para a requisição ({@link TenantContext#currentTenantId()}: a
 * claim do JWT, o tenant padrão de um token sem claim ou a personificação que ele já conferiu). O
 * valor do cliente só é <em>conferido</em> contra ela:
 *
 * <ul>
 *   <li>ausente ou igual ao tenant da requisição: vale o da requisição;</li>
 *   <li>{@code "all"} ou outro tenant: só para quem tem o papel ADMIN/SUPERADMIN no token (a mesma
 *       regra do {@link AdminRoleInterceptor}); sem ele, {@link TenantAccessDeniedException} (403).
 *       Recusar em vez de ignorar segue o {@code TENANT_MISMATCH} do filtro: quem pediu outro tenant
 *       e recebesse o próprio em silêncio leria (ou decidiria) a coisa errada achando que é a
 *       certa.</li>
 * </ul>
 *
 * <p>Com a autenticação desligada (dev/E2E, {@code archflow.security.auth.enabled=false}) não há
 * identidade para conferir, e o valor do cliente passa como veio — o comportamento de antes. O
 * modo é lido da propriedade, e não da ausência de token: com auth ligada, uma requisição que
 * chegasse aqui sem papéis (outra forma de autenticação, um caminho público mal configurado) cairia
 * no modo permissivo; assim ela é tratada como um usuário comum.
 */
@Component
public class TenantScope {

    private static final Logger log = LoggerFactory.getLogger(TenantScope.class);

    /** Sentinela de "todos os tenants" que a UI do superadmin já envia. */
    public static final String ALL = "all";

    private final boolean authEnabled;

    public TenantScope(@Value("${archflow.security.auth.enabled:false}") boolean authEnabled) {
        this.authEnabled = authEnabled;
    }

    /**
     * O recorte de uma listagem.
     *
     * @param requested o {@code tenantId} que o cliente mandou (pode ser nulo)
     * @return com auth ligada, o tenant da requisição, outro tenant (admin) ou {@link #ALL} (admin) —
     *         nunca nulo; com auth desligada, o valor pedido sem espaços, ou {@code null} quando
     *         ausente (cada chamador mantém o próprio padrão de dev)
     * @throws TenantAccessDeniedException quando o pedido sai do tenant da requisição sem o papel
     */
    public String forListing(String requested) {
        String pedido = requested == null || requested.isBlank() ? null : requested.trim();
        if (!authEnabled) {
            return pedido;
        }
        String proprio = TenantContext.currentTenantId();
        if (pedido == null || pedido.equals(proprio)) {
            return proprio;
        }
        boolean todos = ALL.equalsIgnoreCase(pedido);
        if (!callerIsAdmin()) {
            log.warn("Recusado tenantId '{}' de quem não é admin (tenant da requisição: {})", pedido, proprio);
            throw new TenantAccessDeniedException(todos
                    ? "Viewing all tenants requires the ADMIN role"
                    : "tenantId '" + pedido + "' is not the tenant of the access token; "
                            + "another tenant requires the ADMIN role");
        }
        return todos ? ALL : pedido;
    }

    /**
     * O recorte para ler ou decidir <em>um</em> item: o tenant que o item precisa ter.
     *
     * @return o tenant exigido, ou {@code null} quando qualquer tenant serve (admin com
     *         {@link #ALL}, ou auth desligada sem tenant pedido)
     * @throws TenantAccessDeniedException nas mesmas condições de {@link #forListing}
     */
    public String forItem(String requested) {
        String recorte = forListing(requested);
        return recorte == null || ALL.equalsIgnoreCase(recorte) ? null : recorte;
    }

    /**
     * O recorte para achar um item pelo id onde, antes do RCHFLW-39, o tenant não entrava na busca
     * (detalhe e decisão de aprovação, detalhe de trace). Com auth ligada é o {@link #forItem}. Com
     * auth desligada é {@code null}: a busca continua achando o item de qualquer tenant, como antes —
     * um {@code tenantId} na query ou no corpo que não fosse o do item passaria a dar 404 em dev, onde
     * antes era ignorado.
     */
    public String forItemLookup(String requested) {
        return authEnabled ? forItem(requested) : null;
    }

    /**
     * Exige o papel ADMIN/SUPERADMIN para um dado que só existe agregado para a plataforma inteira (o
     * snapshot do coletor de métricas não tem dimensão de tenant). Recortá-lo pelo tenant da
     * requisição não é possível, e entregá-lo a quem só pode ver o próprio tenant seria mostrar os
     * números — e os nomes das tools — dos outros. Com auth desligada, passa (dev).
     *
     * @param what o que é pedido, para a mensagem do 403
     * @throws TenantAccessDeniedException sem o papel, com a auth ligada
     */
    public void requirePlatformWide(String what) {
        if (authEnabled && !callerIsAdmin()) {
            log.warn("Recusado '{}' (agregado da plataforma) a quem não é admin (tenant da requisição: {})",
                    what, TenantContext.currentTenantId());
            throw new TenantAccessDeniedException(what + " aggregates all tenants and requires the ADMIN role");
        }
    }

    /**
     * O tenant em nome do qual uma ação <em>roda</em> — o dono de um gatilho agendado, de uma invocação
     * do Agent lab (RCHFLW-103). É a regra de {@link #forListing}, com uma diferença: {@link #ALL} não
     * é um tenant, e uma invocação "em todos" não tem dono; aceitá-lo gravaria um gatilho que invoca o
     * agente num tenant que não existe.
     *
     * @param requested o {@code tenantId} que o cliente mandou (pode ser nulo)
     * @return com auth ligada, o tenant da requisição (ausente ou igual) ou o pedido (admin); com auth
     *         desligada, o pedido como veio, sem espaços — o comportamento de antes
     * @throws TenantAccessDeniedException outro tenant sem o papel ADMIN/SUPERADMIN (403)
     * @throws IllegalArgumentException para {@link #ALL}
     */
    public String forAction(String requested) {
        if (requested != null && ALL.equalsIgnoreCase(requested.trim())) {
            throw new IllegalArgumentException("tenantId 'all' is not a tenant; an invocation runs in one tenant");
        }
        return forListing(requested);
    }

    /**
     * {@code true} quando quem pede pode agir sobre um item já gravado com o tenant {@code itemTenant}:
     * o do próprio tenant, ou qualquer um para o admin; sempre, com a auth desligada. Ao contrário de
     * {@link #forItem}, não lança nem registra aviso: serve para recortar uma listagem e para responder
     * como inexistente (404) o item de outro tenant, sem dizer a quem não pode que o id é válido.
     */
    public boolean mayActOn(String itemTenant) {
        if (!authEnabled) {
            return true;
        }
        return TenantContext.currentTenantId().equals(itemTenant) || callerIsAdmin();
    }

    /**
     * {@code true} quando um item de {@code itemTenant} está dentro do recorte de {@link #forItem}.
     * Fora dele, o item é respondido como inexistente — dizer "existe, mas é de outro" já
     * entregaria a quem não pode que o id é válido.
     */
    public static boolean covers(String scope, String itemTenant) {
        return scope == null || scope.equals(itemTenant);
    }

    /** {@code true} com a autenticação ligada — quando há identidade a conferir. */
    public boolean authEnabled() {
        return authEnabled;
    }

    private static boolean callerIsAdmin() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes sra)) {
            return false;
        }
        HttpServletRequest http = sra.getRequest();
        if (http.getAttribute(JwtAuthenticationFilter.ATTR_ROLES) instanceof Collection<?> roles) {
            for (Object role : roles) {
                if (AdminRoleInterceptor.isAdminRole(String.valueOf(role))) {
                    return true;
                }
            }
        }
        return false;
    }
}
