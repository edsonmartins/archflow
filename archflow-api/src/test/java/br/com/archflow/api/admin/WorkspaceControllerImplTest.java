package br.com.archflow.api.admin;

import br.com.archflow.api.admin.dto.TenantDto;
import br.com.archflow.api.admin.dto.UserDto;
import br.com.archflow.api.admin.dto.UserDto.*;
import br.com.archflow.api.admin.impl.TenantControllerImpl;
import br.com.archflow.api.admin.impl.WorkspaceControllerImpl;
import br.com.archflow.api.agent.mcp.ContadorDeUso;
import br.com.archflow.api.agent.mcp.TabelaDePrecos;
import br.com.archflow.api.config.ImpersonationFilter;
import br.com.archflow.api.config.JwtAuthenticationFilter;
import br.com.archflow.api.usage.InMemoryRepositorioDeUso;
import br.com.archflow.api.usage.LancamentoDeUso;
import br.com.archflow.api.usage.RegistroDeUso;
import br.com.archflow.api.web.workflow.InMemoryWorkflowRuntimeStore;
import br.com.archflow.model.security.Role;
import br.com.archflow.model.security.User;
import br.com.archflow.security.apikey.ApiKeyService;
import br.com.archflow.security.auth.AuthService;
import br.com.archflow.security.auth.InMemoryUserRepository;
import br.com.archflow.security.auth.WorkspaceUserService;
import br.com.archflow.security.auth.WorkspaceUserService.WorkspaceUserException;
import br.com.archflow.security.jwt.JwtService;
import br.com.archflow.security.password.PasswordService;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * O workspace com dados reais (RCHFLW-85/75): usuários do login (sem "João Silva" nem "Maria
 * Santos"), resumo contado do que existe e limites só quando o tenant tem cadastro.
 */
@DisplayName("WorkspaceControllerImpl")
class WorkspaceControllerImplTest {

    private static final String TENANT = "tenant_acme";

    private InMemoryUserRepository userRepository;
    private PasswordService passwords;
    private WorkspaceUserService users;
    private InMemoryWorkflowRuntimeStore store;
    private RegistroDeUso registro;
    private ApiKeyService apiKeys;
    private TenantControllerImpl tenants;
    private WorkspaceControllerImpl controller;
    private User admin;

    @BeforeEach
    void setUp() {
        userRepository = new InMemoryUserRepository();
        passwords = new PasswordService(4);
        users = new WorkspaceUserService(userRepository, passwords, Duration.ofHours(72));
        store = new InMemoryWorkflowRuntimeStore();
        registro = new RegistroDeUso(new InMemoryRepositorioDeUso(), TabelaDePrecos.vazia());
        apiKeys = mock(ApiKeyService.class);
        tenants = new TenantControllerImpl(false);
        controller = new WorkspaceControllerImpl(null, users, store, registro, apiKeys, tenants);

        admin = new User();
        admin.setUsername("admin");
        admin.setEmail("admin@acme.com");
        admin.setFirstName("Ada");
        admin.setLastName("Admin");
        admin.setTenantId(TENANT);
        admin.setPasswordHash(passwords.hash("senha-do-admin"));
        admin.addRole(Role.createAdminRole());
        userRepository.save(admin);

        var request = new MockHttpServletRequest("GET", "/api/admin/workspace");
        request.setAttribute(ImpersonationFilter.ATTR_TENANT_ID, TENANT);
        request.setAttribute(JwtAuthenticationFilter.ATTR_USER_ID, admin.getId());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Nested
    @DisplayName("Summary")
    class Summary {

        @Test
        @DisplayName("sem medição e sem cadastro: zeros medidos, sem plano e sem limites — nada fixo no código")
        void emptyWorkspaceShowsZerosAndNoLimits() {
            var summary = controller.getSummary();

            assertThat(summary.tenantId()).isEqualTo(TENANT);
            assertThat(summary.tenantName()).isEqualTo(TENANT);
            assertThat(summary.plan()).isNull();
            assertThat(summary.limits()).isNull();
            assertThat(summary.executionsToday()).isZero();
            assertThat(summary.tokensThisMonth()).isZero();
            assertThat(summary.workflowCount()).isZero();
            assertThat(summary.userCount()).isEqualTo(1);
            assertThat(summary.usageThisMonth().executions()).isZero();
            assertThat(summary.usageThisMonth().costCents()).isNull();
        }

        @Test
        @DisplayName("conta workflows, execuções de hoje, usuários, chaves e tokens medidos do tenant")
        void countsWhatExists() {
            store.putWorkflow(TENANT, "wf-1", Map.of("id", "wf-1"));
            store.putWorkflow("outro", "wf-2", Map.of("id", "wf-2"));
            store.createExecution(TENANT, "wf-1", "Fluxo");
            store.createExecution(TENANT, "wf-1", "Fluxo");
            when(apiKeys.countActiveKeys(TENANT)).thenReturn(3);
            ContadorDeUso uso = new ContadorDeUso();
            uso.somar("openrouter", "openrouter/m", 1_200, 300);
            registro.registrar(TENANT, "exec-1", LancamentoDeUso.ORIGEM_FLUXO, uso);

            var summary = controller.getSummary();

            assertThat(summary.workflowCount()).isEqualTo(1);
            assertThat(summary.executionsToday()).isEqualTo(2);
            assertThat(summary.apiKeyCount()).isEqualTo(3);
            assertThat(summary.tokensThisMonth()).isEqualTo(1_500);
            assertThat(summary.usageThisMonth().executions()).isEqualTo(1);
            assertThat(summary.usageThisMonth().executionsWithoutPrice()).isEqualTo(1);
        }

        @Test
        @DisplayName("com cadastro do tenant: nome, plano e limites são os do cadastro")
        void limitsComeFromTheTenantRecord() {
            tenants.createTenant(new TenantDto.CreateTenantRequest("Acme", TENANT, "a@acme.com", "Varejo",
                    "enterprise", null, new TenantDto.TenantLimitsDto(50, 2_000_000, 7, 4, List.of(), List.of()),
                    List.of("openrouter/m")));

            var summary = controller.getSummary();

            assertThat(summary.tenantName()).isEqualTo("Acme");
            assertThat(summary.plan()).isEqualTo("enterprise");
            assertThat(summary.limits().tokensPerMonth()).isEqualTo(2_000_000);
            assertThat(summary.limits().maxUsers()).isEqualTo(4);
            assertThat(summary.limits().allowedModels()).containsExactly("openrouter/m");
        }

        /**
         * RCHFLW-44: o tenant vem só do atributo que o ImpersonationFilter resolveu. O header cru
         * pulava a checagem contra o JWT (e o X-Impersonate-Tenant, a do papel).
         */
        @Test
        @DisplayName("ignora X-Tenant-Id / X-Impersonate-Tenant crus; sem atributo nem fallback, recusa")
        void ignoresRawTenantHeaders() {
            var raw = new MockHttpServletRequest("GET", "/api/admin/workspace");
            raw.addHeader("X-Tenant-Id", "tenant_b");
            raw.addHeader("X-Impersonate-Tenant", "tenant_c");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(raw));

            assertThatThrownBy(controller::getSummary).isInstanceOf(IllegalStateException.class);
            var dev = new WorkspaceControllerImpl("tenant_demo", users, store, registro, apiKeys, tenants);
            assertThat(dev.getSummary().tenantId()).as("fallback de dev, nunca o header").isEqualTo("tenant_demo");
        }
    }

    @Nested
    @DisplayName("Users")
    class Users {

        @Test
        @DisplayName("lista os usuários reais do login no tenant — sem dados de demonstração")
        void listsRealUsersOnly() {
            List<UserDto> list = controller.listUsers();

            assertThat(list).singleElement().satisfies(u -> {
                assertThat(u.id()).isEqualTo(admin.getId());
                assertThat(u.name()).isEqualTo("Ada Admin");
                assertThat(u.role()).isEqualTo("ADMIN");
                assertThat(u.status()).isEqualTo("active");
            });
        }

        @Test
        @DisplayName("convidar devolve o token uma vez e cria um pendente que só entra depois do aceite")
        void inviteCreatesPendingAccessUntilAccepted() {
            InviteUserResponse response = controller.inviteUser(
                    new InviteUserRequest("viewer.teste@example.com", "VIEWER"));

            assertThat(response.invitationToken()).startsWith(response.user().id() + ".");
            assertThat(response.expiresAt()).isNotBlank();
            assertThat(response.user().status()).isEqualTo("invited");
            assertThat(controller.listUsers()).extracting(UserDto::email).contains("viewer.teste@example.com");
            // A lista nunca devolve o token: ele só existe na resposta do convite.
            assertThat(controller.listUsers().toString()).doesNotContain(response.invitationToken());

            users.acceptInvitation(response.invitationToken(), "senha-nova-123");
            var auth = new AuthService(new JwtService("uma-chave-de-teste-bem-longa-para-o-hmac-sha-256-0123456789"),
                    passwords, userRepository);
            assertThat(auth.authenticate("viewer.teste@example.com", "senha-nova-123").roles())
                    .containsExactly("VIEWER");
            assertThat(controller.listUsers()).filteredOn(u -> u.email().equals("viewer.teste@example.com"))
                    .singleElement().extracting(UserDto::status).isEqualTo("active");
        }

        @Test
        @DisplayName("trocar o papel muda o usuário do login; o papel é o nome do backend")
        void updateRoleChangesTheRealUser() {
            var invited = controller.inviteUser(new InviteUserRequest("ana@acme.com", "DESIGNER"));

            UserDto updated = controller.updateUserRole(invited.user().id(), new UpdateRoleRequest("EXECUTOR"));

            assertThat(updated.role()).isEqualTo("EXECUTOR");
            assertThat(userRepository.findById(invited.user().id()).orElseThrow().hasRole(Role.ROLE_EXECUTOR))
                    .isTrue();
            assertThatThrownBy(() -> controller.updateUserRole(invited.user().id(), new UpdateRoleRequest("editor")))
                    .hasMessageContaining("Unknown role: editor");
        }

        @Test
        @DisplayName("o administrador autenticado não troca o próprio papel nem se remove")
        void actorComesFromThePrincipal() {
            assertThatThrownBy(() -> controller.updateUserRole(admin.getId(), new UpdateRoleRequest("VIEWER")))
                    .isInstanceOfSatisfying(WorkspaceUserException.class,
                            e -> assertThat(e.reason()).isEqualTo(WorkspaceUserException.Reason.FORBIDDEN));
            assertThatThrownBy(() -> controller.removeUser(admin.getId()))
                    .isInstanceOfSatisfying(WorkspaceUserException.class,
                            e -> assertThat(e.reason()).isEqualTo(WorkspaceUserException.Reason.FORBIDDEN));
        }

        @Test
        @DisplayName("remover apaga o usuário do login; revogar apaga o convite")
        void removeAndRevokeChangeRealAccess() {
            var active = controller.inviteUser(new InviteUserRequest("ana@acme.com", "VIEWER"));
            users.acceptInvitation(active.invitationToken(), "senha-nova-123");
            var pending = controller.inviteUser(new InviteUserRequest("bia@acme.com", "VIEWER"));

            controller.removeUser(active.user().id());
            controller.revokeInvite(pending.user().id());

            assertThat(userRepository.findById(active.user().id())).isEmpty();
            assertThat(userRepository.findById(pending.user().id())).isEmpty();
            assertThat(controller.listUsers()).extracting(UserDto::id).containsExactly(admin.getId());
        }

        @Test
        @DisplayName("convite sem corpo é a recusa tipada BODY_REQUIRED, e a resposta leva o código para a UI")
        void missingBodyIsATypedRefusalWithCode() {
            assertThatThrownBy(() -> controller.inviteUser(null))
                    .isInstanceOfSatisfying(WorkspaceUserException.class, e -> {
                        assertThat(e.code()).isEqualTo(WorkspaceUserException.Code.BODY_REQUIRED);
                        var response = br.com.archflow.api.web.admin.SpringWorkspaceController.refusal(e);
                        assertThat(response.getStatusCode().value()).isEqualTo(400);
                        assertThat(response.getBody()).containsEntry("code", "BODY_REQUIRED")
                                .containsEntry("error", "INVALID")
                                .containsEntry("message", "Request body is required");
                    });
        }

        @Test
        @DisplayName("os papéis expostos são os do backend, com as permissões de cada um")
        void rolesAreTheBackendOnes() {
            assertThat(controller.listRoles()).extracting(RoleDto::name)
                    .containsExactly("ADMIN", "DESIGNER", "EXECUTOR", "VIEWER");
            assertThat(controller.listRoles()).filteredOn(r -> r.name().equals("VIEWER")).singleElement()
                    .extracting(RoleDto::permissions).asInstanceOf(InstanceOfAssertFactories.LIST)
                    .containsExactly("agent:read", "execution:read", "workflow:read");
        }
    }
}
