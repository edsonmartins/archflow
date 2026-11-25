package br.com.archflow.api.admin.impl;

import br.com.archflow.api.admin.WorkspaceController;
import br.com.archflow.api.admin.dto.TenantDto;
import br.com.archflow.api.admin.dto.UserDto;
import br.com.archflow.api.admin.dto.UserDto.*;
import br.com.archflow.api.admin.dto.WorkspaceSummaryDto;
import br.com.archflow.api.config.ImpersonationFilter;
import br.com.archflow.api.config.JwtAuthenticationFilter;
import br.com.archflow.api.usage.RegistroDeUso;
import br.com.archflow.api.usage.UsoSomado;
import br.com.archflow.api.web.workflow.WorkflowRuntimeStore;
import br.com.archflow.model.security.Permission;
import br.com.archflow.model.security.Role;
import br.com.archflow.model.security.User;
import br.com.archflow.security.apikey.ApiKeyService;
import br.com.archflow.security.auth.WorkspaceUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * O workspace do tenant com dados reais (RCHFLW-85/75).
 *
 * <p>Antes, usuários e chaves viviam em mapas em memória semeados com "João Silva", "Maria Santos" e
 * uma chave {@code af_live_…}: o convite só acrescentava uma linha, remover não revogava nada, as
 * chaves nunca autenticavam, e o resumo mostrava 340 execuções e 4,2M de 5M tokens fixos no código.
 * Agora:
 * <ul>
 *   <li>usuários são os do login ({@link WorkspaceUserService}, sobre o {@code UserRepository});</li>
 *   <li>as chaves de API são as do {@link ApiKeyService} — a tela é a de {@code /api/apikeys};</li>
 *   <li>o resumo conta o que existe: workflows e execuções do {@link WorkflowRuntimeStore}, tokens
 *       medidos pelo {@link RegistroDeUso}, e plano/limites do cadastro do tenant — sem cadastro,
 *       sem limite (nunca um limite inventado).</li>
 * </ul>
 */
public class WorkspaceControllerImpl implements WorkspaceController {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceControllerImpl.class);

    /** Dev profile fallback — when set, missing tenant context resolves to this id. */
    private final String fallbackTenantId;
    private final WorkspaceUserService users;
    private final WorkflowRuntimeStore runtimeStore;
    private final RegistroDeUso registroDeUso;
    private final ApiKeyService apiKeys;
    private final TenantControllerImpl tenants;
    private final Clock clock;

    /**
     * @param fallbackTenantId id to return when no tenant context is present; pass {@code null} in
     *                         production so missing tenant context fails fast instead of silently
     *                         serving one shared workspace
     * @param tenants          o cadastro de tenants (plano, status, limites); {@code null} quando não
     *                         há cadastro — o resumo sai sem plano e sem limites
     */
    public WorkspaceControllerImpl(String fallbackTenantId, WorkspaceUserService users,
                                   WorkflowRuntimeStore runtimeStore, RegistroDeUso registroDeUso,
                                   ApiKeyService apiKeys, TenantControllerImpl tenants) {
        this(fallbackTenantId, users, runtimeStore, registroDeUso, apiKeys, tenants, Clock.systemDefaultZone());
    }

    /** Com relógio injetado — "hoje" é o do relógio. */
    public WorkspaceControllerImpl(String fallbackTenantId, WorkspaceUserService users,
                                   WorkflowRuntimeStore runtimeStore, RegistroDeUso registroDeUso,
                                   ApiKeyService apiKeys, TenantControllerImpl tenants, Clock clock) {
        this.fallbackTenantId = fallbackTenantId;
        this.users = Objects.requireNonNull(users, "users");
        this.runtimeStore = Objects.requireNonNull(runtimeStore, "runtimeStore");
        this.registroDeUso = Objects.requireNonNull(registroDeUso, "registroDeUso");
        this.apiKeys = Objects.requireNonNull(apiKeys, "apiKeys");
        this.tenants = tenants;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Resolves the tenant identifier for the current request from the
     * {@code archflow.tenantId} attribute written by {@code ImpersonationFilter}
     * (JWT claim, verified impersonation, or {@code X-Tenant-Id} when no JWT is
     * present). The raw headers are never read here.
     */
    private String resolveTenantId() {
        Object fromAttr = requestAttribute(ImpersonationFilter.ATTR_TENANT_ID);
        // Só o atributo, nunca os headers crus: o filtro já conferiu o X-Tenant-Id contra o
        // tenant do JWT e o X-Impersonate-Tenant contra o papel. Ler o header aqui
        // pulava as duas checagens e devolvia o workspace de outro tenant (RCHFLW-44).
        if (fromAttr instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        if (fallbackTenantId == null) {
            // Production mode: do NOT collapse every unauthenticated caller
            // into a single shared workspace. That masks misconfigured auth
            // and leaks admin data across tenants.
            throw new IllegalStateException(
                    "No tenant context (X-Tenant-Id header absent) and no fallback configured; "
                            + "refusing to serve workspace data");
        }
        log.warn("Tenant resolution fell back to '{}' — wire X-Tenant-Id header or a security filter",
                fallbackTenantId);
        return fallbackTenantId;
    }

    /**
     * Quem está fazendo a operação: o subject do token que o {@link JwtAuthenticationFilter}
     * validou. Nunca um valor do cliente. {@code null} só com a autenticação desligada (dev).
     */
    private static String actorUserId() {
        Object userId = requestAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        return userId instanceof String s && !s.isBlank() ? s : null;
    }

    private static Object requestAttribute(String name) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return sra.getRequest().getAttribute(name);
        }
        return null;
    }

    @Override
    public WorkspaceSummaryDto getSummary() {
        String tenantId = resolveTenantId();
        Optional<TenantDto> tenant = tenants == null ? Optional.empty() : tenants.findTenant(tenantId);
        UsoSomado usoDoMes = registroDeUso.doTenantNoMes(tenantId, registroDeUso.mesAtual());
        WorkspaceSummaryDto.WorkspaceLimitsDto limits = tenant
                .filter(t -> t.limits() != null)
                .map(t -> new WorkspaceSummaryDto.WorkspaceLimitsDto(t.limits().executionsPerDay(),
                        t.limits().tokensPerMonth(), t.limits().maxWorkflows(), t.limits().maxUsers(),
                        t.allowedModels()))
                .orElse(null);
        return new WorkspaceSummaryDto(
                tenantId,
                tenant.map(TenantDto::name).filter(n -> !n.isBlank()).orElse(tenantId),
                tenant.map(TenantDto::plan).orElse(null),
                tenant.map(TenantDto::status).orElse(null),
                executionsToday(runtimeStore, tenantId, clock),
                usoDoMes.tokens(),
                runtimeStore.workflows(tenantId).size(),
                users.listUsers(tenantId).size(),
                apiKeys.countActiveKeys(tenantId),
                WorkspaceSummaryDto.UsageDto.of(usoDoMes),
                limits);
    }

    /**
     * Execuções de fluxo do tenant iniciadas hoje, no fuso do relógio. Compartilhado com a lista de
     * tenants, para os dois números serem o mesmo.
     */
    public static int executionsToday(WorkflowRuntimeStore store, String tenantId, Clock clock) {
        LocalDate today = LocalDate.now(clock);
        return (int) store.executions(tenantId, null, null).stream()
                .map(execution -> execution.get("startedAt"))
                .filter(startedAt -> startedAt != null && startedOn(startedAt.toString(), today, clock.getZone()))
                .count();
    }

    private static boolean startedOn(String startedAt, LocalDate day, ZoneId zone) {
        try {
            return Instant.parse(startedAt).atZone(zone).toLocalDate().equals(day);
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    // ── Users ───────────────────────────────────────────────────────

    @Override
    public List<UserDto> listUsers() {
        return users.listUsers(resolveTenantId()).stream().map(WorkspaceControllerImpl::toDto).toList();
    }

    @Override
    public List<RoleDto> listRoles() {
        return WorkspaceUserService.roles().stream()
                .map(role -> new RoleDto(role.getName(), role.getDescription(), role.getPermissions().stream()
                        .map(Permission::asString)
                        .sorted()
                        .toList()))
                .toList();
    }

    @Override
    public InviteUserResponse inviteUser(InviteUserRequest request) {
        if (request == null) {
            throw new WorkspaceUserService.WorkspaceUserException(
                    WorkspaceUserService.WorkspaceUserException.Code.BODY_REQUIRED, "Request body is required");
        }
        WorkspaceUserService.Invitation invitation =
                users.invite(resolveTenantId(), request.email(), request.role());
        return new InviteUserResponse(toDto(invitation.user()), invitation.token(), iso(invitation.expiresAt()));
    }

    @Override
    public UserDto updateUserRole(String userId, UpdateRoleRequest request) {
        String role = request == null ? null : request.role();
        return toDto(users.changeRole(resolveTenantId(), actorUserId(), userId, role));
    }

    @Override
    public void removeUser(String userId) {
        users.removeUser(resolveTenantId(), actorUserId(), userId);
    }

    @Override
    public void revokeInvite(String userId) {
        users.revokeInvitation(resolveTenantId(), userId);
    }

    static UserDto toDto(User user) {
        String status = user.isInvitationPending() ? "invited" : user.isEnabled() ? "active" : "disabled";
        String role = user.getRoles().stream().map(Role::getName).sorted(Comparator.naturalOrder())
                .findFirst().orElse(null);
        return new UserDto(user.getId(), user.getFullName(), user.getEmail(), role, status,
                iso(user.getLastLoginAt()), iso(user.getInvitationExpiresAt()));
    }

    private static String iso(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant().toString();
    }
}
